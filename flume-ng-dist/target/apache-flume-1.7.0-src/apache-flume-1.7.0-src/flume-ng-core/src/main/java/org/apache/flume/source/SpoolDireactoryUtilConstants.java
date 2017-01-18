package org.apache.flume.source;

public class SpoolDireactoryUtilConstants {

	public static final String PROPERTY_PREFIX = "kafka.";

	  /* Properties */

	  public static final String TOPIC = "topic";
	  public static final String BATCH_SIZE = "batchSize";
	  public static final String BROKER_LIST_KEY = "bootstrap.servers";
	  public static final String REQUIRED_ACKS_KEY = "request.required.acks";
	  public static final String BROKER_LIST_FLUME_KEY = "brokerList";
	  public static final String REQUIRED_ACKS_FLUME_KEY = "requiredAcks";
	  public static final String JAVA_AUTHENTICATION_PATH = "jaasString";
	  public static final String SECURITY_PROTOCAL = "securityProtocol";
	  public static final String KEY_SERIALIZER = "key.serializer";
	  public static final String VALUE_SERIALIZER = "value.serializer";
	  public static final String KRB_CONF_PATH = "krbConfPath";
	  public static final String THREADS = "threads";
	  public static final String QUEUE_SIZE = "queue_size";
	  public static final String THREADPOLL_TIMEOUT = "threadpoll_timeout_ms";
	  
	  
	  
	  public static final int DEFAULT_THREADS = 1;
	  public static final long DEFAULT_THREADPOLL_TIMEOUT = 60 * 1000;
	  public static final long DEFAULT_QUEUE_SIZE = 10000;
	  public static final int DEFAULT_BATCH_SIZE = 100;
	  public static final String DEFAULT_TOPIC = "default-flume-topic";
	  public static final String DEFAULT_REQUIRED_ACKS = "1";
	  public static final String DEFAULT_SECURITY_PROTOCAL = "SASL_PLAINTEXT";
}
