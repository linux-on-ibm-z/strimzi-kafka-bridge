package io.ppatierno.kafka.bridge;

import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonLink;
import io.vertx.proton.ProtonSender;

/**
 * Class in charge for reading from Apache Kafka
 * and bridging into AMQP traffic to receivers
 * 
 * @author ppatierno
 */
public class SinkBridgeEndpoint implements BridgeEndpoint {

	private static final Logger LOG = LoggerFactory.getLogger(SinkBridgeEndpoint.class);
	
	private static final String GROUP_ID_MATCH = "/group.id/";
	
	private static final String EVENT_BUS_SEND_COMMAND = "send";
	private static final String EVENT_BUS_SHUTDOWN_COMMAND = "shutdown";
	private static final String EVENT_BUS_HEADER_COMMAND = "command";
	private static final String EVENT_BUS_HEADER_COMMAND_ERROR = "command-error";
	
	// Kafka consumer related stuff
	private KafkaConsumerRunner kafkaConsumerRunner;
	private Thread kafkaConsumerThread;
	
	// Event Bus communication stuff between Kafka consumer thread
	// and main Vert.x event loop
	private Vertx vertx;
	private Queue<ConsumerRecord<String, byte[]>> queue;
	private String ebQueue;
	private MessageConsumer<String> ebConsumer;
	
	private MessageConverter<String, byte[]> converter;
	
	private int deliveryTag;
	
	/**
	 * Constructor
	 * @param vertx		Vert.x instance
	 */
	public SinkBridgeEndpoint(Vertx vertx) {
		this.vertx = vertx;
		this.queue = new ConcurrentLinkedQueue<ConsumerRecord<String, byte[]>>();
		this.converter = new DefaultMessageConverter();
		// generate an UUID as name for the Vert.x EventBus internal queue
		this.ebQueue = String.format("%s.%s.%s", 
				Bridge.class.getSimpleName().toLowerCase(), 
				SinkBridgeEndpoint.class.getSimpleName().toLowerCase(), 
				UUID.randomUUID().toString());
		LOG.info("Event Bus queue : {}", this.ebQueue);
		
		this.deliveryTag = 0;
	}
	
	@Override
	public void open() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void close() {
		this.kafkaConsumerRunner.shutdown();
		if (this.ebConsumer != null)
			this.ebConsumer.unregister();
	}
	
	@Override
	public void handle(ProtonLink<?> link) {
		
		if (!(link instanceof ProtonSender)) {
			throw new IllegalArgumentException("This Proton link must be a sender");
		}
		
		ProtonSender sender = (ProtonSender)link;
		sender.setSource(sender.getRemoteSource())
		.closeHandler(this::processCloseSender)
		.open();
		
		// address is like this : [topic]/group.id/[group.id]
		String address = sender.getRemoteSource().getAddress();
		
		String groupId = address.substring(address.indexOf(SinkBridgeEndpoint.GROUP_ID_MATCH) + 
											SinkBridgeEndpoint.GROUP_ID_MATCH.length());
		String topic = address.substring(0, address.indexOf(SinkBridgeEndpoint.GROUP_ID_MATCH));
		
		LOG.info("topic {} group.id {}", topic, groupId);
		
		// creating configuration for Kafka consumer
		Properties props = new Properties();
		props.put(BridgeConfig.BOOTSTRAP_SERVERS, BridgeConfig.getBootstrapServers());
		props.put(BridgeConfig.KEY_DESERIALIZER, BridgeConfig.getKeyDeserializer());
		props.put(BridgeConfig.VALUE_DESERIALIZER, BridgeConfig.getValueDeserializer());
		props.put(BridgeConfig.GROUP_ID, groupId);
		props.put(BridgeConfig.AUTO_OFFSET_RESET, "earliest"); // TODO : it depends on x-opt-bridge.offset inside the AMQP message
		
		// create and start new thread for reading from Kafka
		this.kafkaConsumerRunner = new KafkaConsumerRunner(props, topic, this.queue, this.vertx, this.ebQueue);
		this.kafkaConsumerThread = new Thread(kafkaConsumerRunner);
		this.kafkaConsumerThread.start();
		
		// message sending on AMQP link MUST happen on Vert.x event loop due to
		// the access to the sender object provided by Vert.x handler
		// (we MUST avoid to access it from other threads; i.e. Kafka consumer thread)
		this.ebConsumer = this.vertx.eventBus().consumer(this.ebQueue, ebMessage -> {
			
			switch (ebMessage.headers().get(SinkBridgeEndpoint.EVENT_BUS_HEADER_COMMAND)) {
				
				case SinkBridgeEndpoint.EVENT_BUS_SEND_COMMAND:
					
					ConsumerRecord<String, byte[]> record = null;
					while ((record = this.queue.poll()) != null) {
						
						Message message = converter.toAmqpMessage(record);
				        
						sender.send(ProtonHelper.tag(String.valueOf(++this.deliveryTag)), message, delivery -> {
							LOG.info("Message delivered to  {}", sender.getSource().getAddress());
						});
					}
					break;
				
				case SinkBridgeEndpoint.EVENT_BUS_SHUTDOWN_COMMAND:
					
					// no partitions assigned, the AMQP link and Kafka consumer will be closed
					sender.setCondition(new ErrorCondition(Symbol.getSymbol(Bridge.AMQP_ERROR_NO_PARTITIONS), 
							ebMessage.headers().get(SinkBridgeEndpoint.EVENT_BUS_HEADER_COMMAND_ERROR)));
					sender.close();
					
					this.kafkaConsumerRunner.shutdown();
					break;
			}
			
		});
	}
	
	/**
	 * Handle for detached link by the remote receiver
	 * @param ar		async result with info on related Proton sender
	 */
	private void processCloseSender(AsyncResult<ProtonSender> ar) {
		
		if (ar.succeeded()) {
			
			LOG.info("Remote detached");
			
			this.kafkaConsumerRunner.shutdown();
			ar.result().close();
		}
	}
	
	/**
	 * Class for reading from Kafka in a multi-threading way
	 * 
	 * @author ppatierno
	 */
	public class KafkaConsumerRunner implements Runnable {

		private AtomicBoolean closed;
		private Consumer<String, byte[]> consumer;
		private String topic;
		Queue<ConsumerRecord<String, byte[]>> queue;
		private Vertx vertx;
		private String ebQueue;
		
		/**
		 * Constructor
		 * @param props		Properties for KafkaConsumer instance
		 * @param topic		Topic to publish messages
		 * @param queue		Internal queue for sharing Kafka records with Vert.x EventBus consumer 
		 * @param vertx		Vert.x instance
		 * @param ebQueue	Vert.x EventBus unique name queue for sharing Kafka records
		 */
		public KafkaConsumerRunner(Properties props, String topic, Queue<ConsumerRecord<String, byte[]>> queue, Vertx vertx, String ebQueue) {
			
			this.closed = new AtomicBoolean(false);
			this.consumer = new KafkaConsumer<>(props);
			this.topic = topic;
			this.queue = queue;
			this.vertx = vertx;
			this.ebQueue = ebQueue;
		}
		
		@Override
		public void run() {
			
			LOG.info("Apache Kafka consumer runner started ...");
			
			this.consumer.subscribe(Arrays.asList(this.topic), new ConsumerRebalanceListener() {
				
				@Override
				public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
					LOG.info("Partitions revoked {}", partitions.size());
					
					if (!partitions.isEmpty()) {
						for (TopicPartition partition : partitions) {
							LOG.info("topic {} partition {}", partition.topic(), partition.partition());
						}
					}
				}
				
				@Override
				public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
					LOG.info("Partitions assigned {}", partitions.size());
					if (!partitions.isEmpty()) {
						
						for (TopicPartition partition : partitions) {
							LOG.info("topic {} partition {}", partition.topic(), partition.partition());
						}
					} else {
						
						DeliveryOptions options = new DeliveryOptions();
						options.addHeader(SinkBridgeEndpoint.EVENT_BUS_HEADER_COMMAND, SinkBridgeEndpoint.EVENT_BUS_SHUTDOWN_COMMAND);
						options.addHeader(SinkBridgeEndpoint.EVENT_BUS_HEADER_COMMAND_ERROR, "All partitions already have a receiver");
						
						// no partitions assigned, the AMQP link and Kafka consumer will be closed
						vertx.eventBus().send(ebQueue, "", options);
					}
				}
			});
			
			try {
				while (!this.closed.get()) {
					
					ConsumerRecords<String, byte[]> records = this.consumer.poll(100);
				    for (ConsumerRecord<String, byte[]> record : records)  {
				        
				    	LOG.info("Received from Kafka partition {} [{}], key = {}, value = {}", record.partition(), record.offset(), record.key(), new String(record.value()));
				    	this.queue.add(record);				    	
				    }
				    
				    if (!records.isEmpty()) {
				    	
				    	DeliveryOptions options = new DeliveryOptions();
						options.addHeader(SinkBridgeEndpoint.EVENT_BUS_HEADER_COMMAND, SinkBridgeEndpoint.EVENT_BUS_SEND_COMMAND);
						
				    	this.vertx.eventBus().send(this.ebQueue, "", options);
				    }
				}
			} catch (WakeupException e) {
				if (!closed.get()) throw e;
			} finally {
				this.consumer.close();
			}
			
			LOG.info("Apache Kafka consumer runner stopped ...");
		}
		
		/**
		 * Shutdown the consumer runner
		 */
		public void shutdown() {
			this.closed.set(true);
			this.consumer.wakeup();
		}
		
	}
}