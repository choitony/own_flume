/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flume.source.kafka;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import kafka.cluster.BrokerEndPoint;
import kafka.common.Topic;
import kafka.utils.ZKGroupTopicDirs;
import kafka.utils.ZkUtils;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.conf.LogPrivacyUtil;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.instrumentation.kafka.KafkaSourceCounter;
import org.apache.flume.source.AbstractPollableSource;
import org.apache.flume.source.avro.AvroFlumeEvent;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.protocol.SecurityProtocol;
import org.apache.kafka.common.security.JaasUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import scala.Option;

import static org.apache.flume.source.kafka.KafkaSourceConstants.*;
import static scala.collection.JavaConverters.asJavaListConverter;

/**
 * A Source for Kafka which reads messages from kafka topics.
 *
 * <tt>kafka.bootstrap.servers: </tt> A comma separated list of host:port pairs
 * to use for establishing the initial connection to the Kafka cluster. For
 * example host1:port1,host2:port2,... <b>Required</b> for kafka.
 * <p>
 * <tt>kafka.consumer.group.id: </tt> the group ID of consumer group.
 * <b>Required</b>
 * <p>
 * <tt>kafka.topics: </tt> the topic list separated by commas to consume
 * messages from. <b>Required</b>
 * <p>
 * <tt>maxBatchSize: </tt> Maximum number of messages written to Channel in one
 * batch. Default: 1000
 * <p>
 * <tt>maxBatchDurationMillis: </tt> Maximum number of milliseconds before a
 * batch (of any size) will be written to a channel. Default: 1000
 * <p>
 * <tt>kafka.consumer.*: </tt> Any property starting with "kafka.consumer" will
 * be passed to the kafka consumer So you can use any configuration supported by
 * Kafka 0.9.0.X <tt>useFlumeEventFormat: </tt> Reads events from Kafka Topic as
 * an Avro FlumeEvent. Used in conjunction with useFlumeEventFormat (Kafka Sink)
 * or parseAsFlumeEvent (Kafka Channel)
 * <p>
 */
public class KafkaSource extends AbstractPollableSource implements Configurable {
	private static final Logger log = LoggerFactory.getLogger(KafkaSource.class);

	// Constants used only for offset migration zookeeper connections
	private static final int ZK_SESSION_TIMEOUT = 30000;
	private static final int ZK_CONNECTION_TIMEOUT = 30000;

	private Context context;
	private KafkaSourceCounter counter;
	private KafkaConsumer<String, byte[]> consumer;
	private Iterator<ConsumerRecord<String, byte[]>> it;

	private final List<Event> eventList = new ArrayList<Event>();
	private Map<TopicPartition, OffsetAndMetadata> tpAndOffsetMetadata;

	private String groupId = DEFAULT_GROUP_ID;
	private String topic;

	private KafkaSourceManager manager = null;

	@Override
	protected Status doProcess() throws EventDeliveryException {

		List<Event> eventList;
		eventList = manager.getFromManagerList();
		if (eventList == null) {
			log.info("<" + topic + "> kafka source get no message from kafka now, return state backoff");
			return Status.BACKOFF;
		} else {
			getChannelProcessor().processEventBatch(eventList);
		}

		if (eventList.size() > 0) {
			eventList.clear();
			return Status.READY;
		} else {
			return Status.BACKOFF;
		}
	}

	/**
	 * We configure the source and generate properties for the Kafka Consumer
	 *
	 * Kafka Consumer properties are generated as follows: 1. Generate a
	 * properties object with some static defaults that can be overridden if
	 * corresponding properties are specified 2. We add the configuration users
	 * added for Kafka (parameters starting with kafka.consumer and must be
	 * valid Kafka Consumer properties 3. Add source level properties (with no
	 * prefix)
	 * 
	 * @param context
	 */
	@Override
	protected void doConfigure(Context context) throws FlumeException {
		this.context = context;

		String groupIdProperty = context.getString(KAFKA_CONSUMER_PREFIX + ConsumerConfig.GROUP_ID_CONFIG);
		if (groupIdProperty != null && !groupIdProperty.isEmpty()) {
			groupId = groupIdProperty; // Use the new group id property
		}

		if (groupId == null || groupId.isEmpty()) {
			groupId = DEFAULT_GROUP_ID;
			log.info("Group ID was not specified. Using {} as the group id.", groupId);
		}

		topic = context.getString(KafkaSourceConstants.TOPICS);

		if (counter == null) {
			counter = new KafkaSourceCounter(getName());
		}
	}

	/**
	 * Helper function to convert a map of CharSequence to a map of String.
	 */
	private static Map<String, String> toStringMap(Map<CharSequence, CharSequence> charSeqMap) {
		Map<String, String> stringMap = new HashMap<String, String>();
		for (Map.Entry<CharSequence, CharSequence> entry : charSeqMap.entrySet()) {
			stringMap.put(entry.getKey().toString(), entry.getValue().toString());
		}
		return stringMap;
	}

	@Override
	protected void doStart() throws FlumeException {
		log.info("Starting {}...", this);

		manager = new KafkaSourceManager(context);
		log.info("Kafka source {} started", getName());
	}

	@Override
	protected void doStop() throws FlumeException {
		manager.stop();
		log.info("Kafka Source {} stopped. Metrics: {}", getName(), counter);
	}
}
