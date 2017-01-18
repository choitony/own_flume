package org.apache.flume.source.kafka;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.source.avro.AvroFlumeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Optional;

import scala.annotation.implicitNotFound;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

public class KafkaSourceManager {

	private static final Logger logger = LoggerFactory.getLogger(KafkaSource.class);

	private int consumerNums;
	private ExecutorService threadPool;

	private String groupId;
	private boolean needShutDown = false;
	private LinkedList<List<Event>> managerList;
	private int maxManagerList;
	private int batchSize;
	private int maxBatchDurationMillis;
	private Object managerListLock = new Object();
	private Properties kafkaProps;
	private boolean useAvroEventFormat;
	ArrayList<ConsumerRunnable> consumerList;
	private Context context;
	private String bootstrapServers;

	private void setConsumerProps(Context ctx) {
		kafkaProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, KafkaSourceConstants.DEFAULT_KEY_DESERIALIZER);
		kafkaProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaSourceConstants.DEFAULT_VALUE_DESERIALIZER);
		// Defaults overridden based on config
		kafkaProps.putAll(ctx.getSubProperties(KafkaSourceConstants.KAFKA_CONSUMER_PREFIX));
		// These always take precedence over config
		kafkaProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		if (groupId != null) {
			kafkaProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		}
		kafkaProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, KafkaSourceConstants.DEFAULT_AUTO_COMMIT);
	}

	public KafkaSourceManager(Context context) {
		this.context = context;
		this.bootstrapServers = context.getString(KafkaSourceConstants.BOOTSTRAP_SERVERS);
		this.kafkaProps = new Properties();
		setConsumerProps(context);

		this.consumerNums = context.getInteger(KafkaSourceConstants.THREADS, 1);
		this.maxManagerList = context.getInteger(KafkaSourceConstants.MAXMANAGERLIST, 10);
		this.managerList = new LinkedList<>();
		this.batchSize = context.getInteger(KafkaSourceConstants.BATCH_SIZE, KafkaSourceConstants.DEFAULT_BATCH_SIZE);
		this.useAvroEventFormat = context.getBoolean(KafkaSourceConstants.AVRO_EVENT,
				KafkaSourceConstants.DEFAULT_AVRO_EVENT);
		this.maxBatchDurationMillis = context.getInteger(KafkaSourceConstants.BATCH_DURATION_MS,
				KafkaSourceConstants.DEFAULT_BATCH_DURATION);

		consumerList = new ArrayList<>();
		threadPool = Executors.newFixedThreadPool(this.consumerNums);
		for (int i = 0; i < this.consumerNums; i++) {
			ConsumerRunnable consumerRunnable = new ConsumerRunnable(useAvroEventFormat, i);
			consumerList.add(consumerRunnable);
			threadPool.execute(consumerRunnable);
		}
	}

	public void stop() {
		threadPool.shutdown();
		needShutDown = true;
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		try {
			if (threadPool.awaitTermination(2, TimeUnit.SECONDS)) {
				threadPool.shutdownNow();
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		for (int i = 0; i < consumerList.size(); i++) {
			consumerList.get(i).stop();
		}
	}

	public int addManagerList(List<Event> eventList) {
		synchronized (managerListLock) {
			if (managerList == null) {
				return 0;
			} else {
				if (managerList.size() >= maxManagerList) {
					return -1;
				} else {
					managerList.add(eventList);
					return 1;
				}
			}
		}
	}

	public List<Event> getFromManagerList() {
		synchronized (managerListLock) {
			if (managerList == null) {
				logger.info("managerList = null");
				return null;
			} else {
				if (managerList.size() < 1) {
					return null;
				} else {
					return managerList.pop();
				}
			}
		}
	}

	class ConsumerRunnable implements Runnable {

		private KafkaConsumer<String, byte[]> consumer;
		private Iterator<ConsumerRecord<String, byte[]>> it;
		private AtomicBoolean rebalanceFlag = new AtomicBoolean(false);
		private boolean useAvroEventFormat;
		private Map<String, String> headers = new HashMap();
		private Map<TopicPartition, OffsetAndMetadata> tpAndOffsetMetadata;
		private Subscriber subscriber;
		private int threadId;
		private BinaryDecoder decoder = null;
		private List<String> topicList;
		private String topicProperty;

		ConsumerRunnable(boolean useAvroEventFormat, int threadId) {

			this.topicProperty = context.getString(KafkaSourceConstants.TOPICS_REGEX);
			if (topicProperty != null && !topicProperty.isEmpty()) {
				// create subscriber that uses pattern-based subscription
				subscriber = new PatternSubscriber(topicProperty);
			} else if ((topicProperty = context.getString(KafkaSourceConstants.TOPICS)) != null
					&& !topicProperty.isEmpty()) {
				// create subscriber that uses topic list subscription
				subscriber = new TopicListSubscriber(topicProperty);
			} else if (subscriber == null) {
				throw new ConfigurationException("At least one Kafka topic must be specified.");
			}
			this.useAvroEventFormat = useAvroEventFormat;
			this.threadId = threadId;
			consumer = new KafkaConsumer<String, byte[]>(kafkaProps);
			tpAndOffsetMetadata = new HashMap<TopicPartition, OffsetAndMetadata>();
			subscriber.subscribe(consumer, new SourceRebalanceListener(rebalanceFlag));
			topicList = (List<String>) subscriber.get();
			long beginPoll = System.currentTimeMillis();
			it = consumer.poll(1000).iterator();
			long endPoll = System.currentTimeMillis();
			logger.info("<" + topicList + "> poll use time  = " + (endPoll - beginPoll));
			logger.info("Kafka source - " + threadId + " started.");
		}

		public void stop() {
			if (consumer != null) {
				consumer.wakeup();
				consumer.close();
			}
		}

		@Override
		public void run() {
			try {
				logger.info("<" + topicList + "> run >>> ");
				while (!needShutDown) {
					final String batchUUID = UUID.randomUUID().toString();
					byte[] kafkaMessage;
					String kafkaKey;
					Event event;
					byte[] eventBody = null;

					// prepare time variables for new batch
					final long nanoBatchStartTime = System.nanoTime();
					final long batchStartTime = System.currentTimeMillis();
					final long maxBatchEndTime = System.currentTimeMillis() + maxBatchDurationMillis;
					ArrayList<Event> eventList = new ArrayList<>();
					Optional<SpecificDatumReader<AvroFlumeEvent>> reader = Optional.absent();

					while (eventList.size() < batchSize && System.currentTimeMillis() < maxBatchEndTime) {
						if (it == null || !it.hasNext()) {
							ConsumerRecords<String, byte[]> records = consumer
									.poll(Math.max(0, maxBatchEndTime - System.currentTimeMillis()));
							it = records.iterator();
							if (rebalanceFlag.get()) {
								logger.info("<" + topicList + "> rebalanceFlag = " + rebalanceFlag.get() + " and exit");
								rebalanceFlag.set(false);
								break;
							}
							// check records after poll
							if (!it.hasNext()) {
								if (logger.isDebugEnabled()) {
									logger.debug("<" + topicList + "> No more data to read");
								}
								// batch time exceeded
								break;
							}
						}

						// get next message
						ConsumerRecord<String, byte[]> message = it.next();
						kafkaKey = message.key();
						kafkaMessage = message.value();

						if (useAvroEventFormat) {
							// Assume the event is in Avro format using the
							// AvroFlumeEvent schema
							// Will need to catch the exception if it is not
							try {
								ByteArrayInputStream in = new ByteArrayInputStream(message.value());
								decoder = DecoderFactory.get().directBinaryDecoder(in, decoder);
								if (!reader.isPresent()) {
									reader = Optional.of(new SpecificDatumReader<AvroFlumeEvent>(AvroFlumeEvent.class));
								}
								// This may throw an exception but it will be
								// caught
								// by
								// the
								// exception handler below and logged at error
								AvroFlumeEvent avroevent = reader.get().read(null, decoder);

								eventBody = avroevent.getBody().array();
								headers = toStringMap(avroevent.getHeaders());
							} catch (Exception exception) {
								logger.error("<" + topicList + "> convert message to avro failed");
							}
						} else {
							eventBody = message.value();
							headers.clear();
							headers = new HashMap<String, String>(4);
						}

						// Add headers to event (timestamp, topic, partition,
						// key)
						// only
						// if they don't exist
						if (!headers.containsKey(KafkaSourceConstants.TIMESTAMP_HEADER)) {
							headers.put(KafkaSourceConstants.TIMESTAMP_HEADER,
									String.valueOf(System.currentTimeMillis()));
						}
						if (!headers.containsKey(KafkaSourceConstants.TOPIC_HEADER)) {
							headers.put(KafkaSourceConstants.TOPIC_HEADER, message.topic());
						}
						if (!headers.containsKey(KafkaSourceConstants.PARTITION_HEADER)) {
							headers.put(KafkaSourceConstants.PARTITION_HEADER, String.valueOf(message.partition()));
						}

						if (kafkaKey != null) {
							headers.put(KafkaSourceConstants.KEY_HEADER, kafkaKey);
						}

						event = EventBuilder.withBody(eventBody, headers);
						eventList.add(event);
						tpAndOffsetMetadata.put(new TopicPartition(message.topic(), message.partition()),
								new OffsetAndMetadata(message.offset() + 1, batchUUID));
					}

					if (eventList.size() > 0) {
						while (addManagerList(eventList) != 1) {
							logger.info("<" + topicList + "> managerList is full, sleep 500ms");
							try {
								Thread.sleep(500);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
						logger.info("<" + topicList + "> successfully add a batch " + eventList.size() + " events into managerlist");
						if (!tpAndOffsetMetadata.isEmpty()) {
							consumer.commitSync(tpAndOffsetMetadata);
							tpAndOffsetMetadata.clear();
						}
					}
				}
			} catch (Throwable exception) {
				logger.error("<" + topicList + "> cuowu!!", exception);
			}
		}

		/**
		 * Helper function to convert a map of CharSequence to a map of String.
		 */
		private Map<String, String> toStringMap(Map<CharSequence, CharSequence> charSeqMap) {
			Map<String, String> stringMap = new HashMap<String, String>();
			for (Map.Entry<CharSequence, CharSequence> entry : charSeqMap.entrySet()) {
				stringMap.put(entry.getKey().toString(), entry.getValue().toString());
			}
			return stringMap;
		}

		/**
		 * This class is a helper to subscribe for topics by using different
		 * strategies
		 */
		public abstract class Subscriber<T> {
			public abstract void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener);

			public T get() {
				return null;
			}
		}

		private class TopicListSubscriber extends Subscriber<List<String>> {
			private List<String> topicList;

			public TopicListSubscriber(String commaSeparatedTopics) {
				this.topicList = Arrays.asList(commaSeparatedTopics.split("^\\s+|\\s*,\\s*|\\s+$"));
			}

			@Override
			public void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener) {
				consumer.subscribe(topicList, listener);
				logger.info("succcessfully subsribe topicList = " + topicList);
			}

			@Override
			public List<String> get() {
				return topicList;
			}
		}

		private class PatternSubscriber extends Subscriber<Pattern> {
			private Pattern pattern;

			public PatternSubscriber(String regex) {
				this.pattern = Pattern.compile(regex);
			}

			@Override
			public void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener) {
				consumer.subscribe(pattern, listener);
			}

			@Override
			public Pattern get() {
				return pattern;
			}
		}

	}
}

class SourceRebalanceListener implements ConsumerRebalanceListener {
	private static final Logger log = LoggerFactory.getLogger(SourceRebalanceListener.class);
	private AtomicBoolean rebalanceFlag;

	public SourceRebalanceListener(AtomicBoolean rebalanceFlag) {
		this.rebalanceFlag = rebalanceFlag;
	}

	// Set a flag that a rebalance has occurred. Then commit already read events
	// to kafka.
	public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
		for (TopicPartition partition : partitions) {
			log.info("topic {} - partition {} revoked.", partition.topic(), partition.partition());
			rebalanceFlag.set(true);
		}
	}

	public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
		for (TopicPartition partition : partitions) {
			log.info("topic {} - partition {} assigned.", partition.topic(), partition.partition());
		}
		log.info("rebalanceLog = " + rebalanceFlag.get());
	}
}
