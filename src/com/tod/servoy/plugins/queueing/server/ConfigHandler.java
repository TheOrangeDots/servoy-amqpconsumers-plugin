package com.tod.servoy.plugins.queueing.server;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.servoy.j2db.plugins.IServerAccess;
import com.servoy.j2db.scripting.FunctionDefinition;
import com.servoy.j2db.scripting.FunctionDefinition.Exist;
import com.servoy.j2db.server.shared.IHeadlessClient;
import com.servoy.j2db.util.Debug;

/**
 * @sample
 * tod.queueing.connection.1.protocol
 * tod.queueing.connection.1.user
 * tod.queueing.connection.1.password
 * tod.queueing.connection.1.host
 * tod.queueing.connection.1.post
 * tod.queueing.connection.1.vhost
 * or
 * tod.queueing.connection.1.url
 * 
 * tod.queueing.channel.1.connection=1 //refers to the connection defined by tod.queueing.connection.1
 * #qos/prefetch settings: https://www.rabbitmq.com/consumer-prefetch.html
 * tod.queueing.channel.1.qos.global=15 // channel-level 
 * tod.queueing.channel.1.qos=10 // consumer-level
 * 
 * tod.queueing.exchange.1.channel=1 //refers to the channel defined by tod.queueing.channel.1
 * tod.queueing.exchange.1.name=rpc
 * tod.queueing.exchange.1.type=direct
 * tod.queueing.exchange.1.durable=true

 * tod.queueing.queue.1.channel=1 //refers to the channel defined by tod.queueing.channel.1
 * tod.queueing.queue.1.name=myQueueName
 * tod.queueing.queue.1.durable=true
 * tod.queueing.queue.1.exclusive=true
 * tod.queueing.queue.1.autodelete=true
 * 
 * tod.queueing.binding.1.queue=1 //refers to the queue defined by tod.queueing.queue.1
 * tod.queueing.binding.1.exchange=1 //refers to the exchange defined by tod.queueing.exchange.1
 * tod.queueing.binding.1.routingkey=myRoutingKey
 * 
 * tod.queueing.consumer.1.handler=jnrMessageQueueHandler://scopes.amqpMessageQueueHandling.consumeRPC
 * tod.queueing.consumer.1.queue=1 //refers to the queue defined by tod.queueing.queue.1
 * tod.queueing.consumer.1.autoAck=false
 * tod.queueing.consumer.1.ack.multiple=true
 * tod.queueing.consumer.1.nack.multiple=true
 * tod.queueing.consumer.1.nack.requeue=true
 * tod.queueing.consumer.1.reject.requeue=true
 * tod.queueing.consumer.1.consumerTag=someTagName
 * tod.queueing.consumer.1.noLocal=true
 * tod.queueing.consumer.1.exclusive=true
 * tod.queueing.consumer.1.options.x-max-priority=10
 * 
 * Default connections/channel/...
 * When defining a type (connection/channel/exchange/queue/binding/consumer), the sequential numbering ('.1.' part in example above) can be omitted
 * This created a 'default' for the specific type
 * 
 * When omitting a reference to a relevant other type, like a queue definition referring to a channel, the default will be used.
 */
public class ConfigHandler {
	private final static String PROPERT_KEY_PREFIX = "tod.queueing.";
	
	//Headless CLient Pool settings
	private final static String CLIENT_POOL_SIZE = "clientpoolsize";
	private final static String EXHAUSTED_ACTION = "exhaustedaction";
	
	//AMQP entities
	private final static String CONNECTION = "connection";
	private final static String CHANNEL = "channel";
	private final static String EXCHANGE = "exchange";
	private final static String QUEUE = "queue";
	private final static String BINDING = "binding";
	private final static String CONSUMER = "consumer";

	//timeout settings
	private final static String CONNECTION_TIMEOUT = "connectiontimeout";
	private final static String HANDSHAKE_TIMEOUT = "handshaketimeout";
	private final static String SHUTDOWN_TIMEOUT = "shutdowntimeout";
	private final static String RPCTIME_OUT = "rpctimeout";

	//Configurable properties
	private final static String DEFAULT = "default";
	private final static String EMPTY = "";
	private final static String OPTIONS = "options";
	
	private final static String HANDLER = "handler";
	
	private final static String USER = "user";
	private final static String PASSWORD = "password";
	private final static String HOST = "host";
	private final static String PORT = "port";
	private final static String VHOST = "vhost";
	
	private final static String TYPE = "type";
	private final static String NAME = "name";
	
	//private final static String EXCHANGE = "exchange";
	private final static String ROUTING_KEY = "routingkey";
	
	private final static String DURABLE = "durable";
	private final static String EXCLUSIVE = "exclusive";
	private final static String AUTO_DELETE = "autodelete";
	
	private final static String CONSUMER_TAG = "consumertag";
	final static String AUTO_ACK = "autoack";
	final static String ACK_MULTIPLE = "ack.multiple";
	final static String NACK_MULTIPLE = "nack.multiple";
	final static String NACK_REQUEUE = "nack.requeue";
	final static String REJECT_REQUEUE = "reject.requeue";
	
	private final static String NO_LOCAL = "nolocal";
	private final static String QOS = "qos";
	private final static String GLOBAL_QOS = "qos.global";
	
	private final static Logger log = LoggerFactory.getLogger(ConfigHandler.class);

	private static HeadlessClientPool pool = null;
	private static IServerAccess serverAccess;
	
	private static HashMap<String, Object> globalConfig = new HashMap<String, Object>();
	private static HashMap<String, HashMap<String, Object>> connections = new HashMap<String, HashMap<String, Object>>();
	private static HashMap<String, HashMap<String, Object>> channels = new HashMap<String, HashMap<String, Object>>();
	private static HashMap<String, HashMap<String, Object>> exchanges = new HashMap<String, HashMap<String, Object>>();
	private static HashMap<String, HashMap<String, Object>> bindings = new HashMap<String, HashMap<String, Object>>();
	private static HashMap<String, HashMap<String, Object>> queues = new HashMap<String, HashMap<String, Object>>();
	private static HashMap<String, HashMap<String, Object>> consumers = new HashMap<String, HashMap<String, Object>>();
	
	//TODO ability to customize initialization through callbacks
	protected static void init(IServerAccess access) {
		serverAccess = access;
		
		Pattern pattern = Pattern.compile(PROPERT_KEY_PREFIX + "(connection|channel|exchange|queue|binding|consumer)(?:\\.(\\d+))?\\.(.*)");
		//m.group(1) > queue:(connection|channel|exchange|queue|binding|consumer)
		//m.group(2) > 2: (\\d+)
		//m.group(3) > vhost: (.*)

		Properties props = access.getSettings();
		Iterator<String> iterator = props.stringPropertyNames()
				   .stream()
				   .filter(x -> x.startsWith(PROPERT_KEY_PREFIX))
				   .iterator();

		HashMap<String, HashMap<String, Object>> configs;
	    HashMap<String, Object> config;
	    String fullKey;
		String key;
		String value;
		Matcher m;
		String indexKey;
		Object realValue;
		
		while (iterator.hasNext()) {
			fullKey = iterator.next();

			value = props.getProperty(fullKey, "").trim();
			if (value.length() == 0) {
				return;
			}

			m = pattern.matcher(fullKey);
			if (m.find()) { // Key must be something like "tod.queueing.queue.2.vhost"
				configs = getDependancyConfigs(m.group(1));

				indexKey = m.group(2) != null ? m.group(2) : DEFAULT;
				config = configs.get(indexKey);

				if (config == null) {
					config = new HashMap<String, Object>();
					configs.put(indexKey, config);
				}

				key = m.group(3);
			} else { // any propertyKey that starts with PROPERT_KEY_PREFIX ('tod.queueing.'), but
						// isn't followed by any of connection|channel|exchange|queue|binding|consumer.
						// Used for HeadlessClient Pool settings for example
				config = globalConfig; // CHECKME what does this look like for properties? And isn't this/shouldn't
										// this be the same as DEFAULT?
				key = fullKey.substring(PROPERT_KEY_PREFIX.length());
			}

			if (value.equalsIgnoreCase("true")) {
				realValue = true;
			} else if (value.equalsIgnoreCase("false")) {
				realValue = false;
			} else if (value.matches("-?\\d+")) {
				realValue = Integer.parseInt(value);
			} else {
				realValue = value; // Must be a String, right?
			}

			// TODO check if the key is supported for m.group(1)
			if (key.startsWith(OPTIONS + ".")) {
				HashMap<String, Object> options = (HashMap<String, Object>) config.get(OPTIONS);

				if (options == null) {
					options = new HashMap<String, Object>();
					config.put(OPTIONS, options);
				}

				options.put(key.substring(8), realValue);
			} else {
				config.put(key, realValue);
			}
		}

		//Start creating all AMQP entities
		String[] amqpEntities = {CONNECTION, CHANNEL, EXCHANGE, QUEUE, BINDING, CONSUMER};
		
		//TODO if having no properties whatsoever for the plugin, do nothing? would mean it would also not do the defaults
		//maybe try to connect to RMQ on localhost with default credentials, but if that fails, suppress the error and stop further initialization
		
		for (String entity : amqpEntities) {
			boolean created;
			HashMap<String, HashMap<String, Object>> entityConfigs = getDependancyConfigs(entity);
			
			//TODO make sure not to start a default connection and channel if theres no relevant config whatsoever
			if (entityConfigs.isEmpty()) { //CHECKME: only do this for amqp entities that have a default?
				entityConfigs.put(DEFAULT, new HashMap<String, Object>());
			}
			
			//TODO remove/close stuff if not used? For example, if there are no consumers, close the channel?
			for (Entry<String, HashMap<String, Object>> entry : entityConfigs.entrySet()) {
				config = entry.getValue();
				created = false;
				
				switch (entity) {
					case CONNECTION:
						created = createConnection(config);
						break;
					case CHANNEL:
						created = createChannel(config);
						break;
					case EXCHANGE:
						created = createExchange(config);
						break;
					case QUEUE:
						created = createQueue(config);
						break;
					case BINDING:
						created = createBinding(config);
						break;
					case CONSUMER:
						created = createConsumer(config);
						break;
				}
				
				if (!created) {
					String configKey = entry.getKey();
					
					log.warn("Skipping configuration for {} '{}'", entity, configKey);
					entityConfigs.remove(configKey);
				}
			}
		}
	}
	
	private static Object applyDependancy(HashMap<String, Object> config, String type) {
		HashMap<String, HashMap<String, Object>> dependancies = getDependancyConfigs(type);
		
		String dependandyId = (String) config.getOrDefault(type, DEFAULT);
		
		if (dependancies.containsKey(dependandyId)) {
			Object dependancy = dependancies.get(dependandyId).get(type);
			config.put(type, dependancy);
			return dependancy;
		}
		return null;
	}
	
	private static HashMap<String, HashMap<String, Object>> getDependancyConfigs(String type) {
		switch (type) {
			case CONNECTION:
				return connections;
			case CHANNEL:
				return channels;
			case EXCHANGE:
				return exchanges;
			case BINDING:
				return bindings;
			case QUEUE:
				return queues;
			case CONSUMER:
				return consumers;
			default:
				Debug.warn("what's happening here?");
		}
		
		return null;
	}
	
	private static Pattern hostSplitter = Pattern.compile("(.*)\\.([A-Za-z]+\\w*)$");
	
	private static FunctionDefinition getFunctionDefinition(URI callback) {
		FunctionDefinition fd = null;
		IHeadlessClient client = null;
		
		try {
			client = getPool().getClient(callback.getScheme());
		} catch (Exception e) {
			log.error("Exception getting client from pool", e);
		}
		
		if (client != null) { //TODO release the client if this is true as well
			if (client.isValid()) {
				Matcher m = hostSplitter.matcher(callback.getHost());
			    if (m.find()) { 
			    	String context = m.group(1);
			    	if (context == "globals") {
			    		context = "";
			    	}
			    	fd = new FunctionDefinition(context, m.group(2));
					if (fd.exists(client.getPluginAccess()) != Exist.METHOD_FOUND) {
						log.error("Client is valid, but method '{}' is not found in solution '{}'", fd.getMethodName(), client.getPluginAccess().getSolutionName());

						fd = null;
					};
			    }
			} else {
				log.error("Client borrowed from pool is invalid");
			}
			client.shutDown(true); //Effectively removing the client from the pool as to not let HC's hanging around that will/might never be used anymore
		    getPool().releaseClient(callback.getScheme(), client, false);
		} else {
			log.error("Failure borrowing client from pool: client is null");
		}
		
	    return fd;
	}
	
	private static boolean createConnection(HashMap<String, Object> config) {
		ConnectionFactory factory = getConnectionFactory(config);
		try {
			config.put(CONNECTION, factory.newConnection()); //TODO if this fails because RMQ is down (java.net.ConnectException), go into a retry pattern, as to establish the connection once RMQ comes back
			return true;
		} catch (IOException | TimeoutException e) {
			log.error("Connection failure", e); //TODO would be useful to log here which connection failed, but the factory doesn't expose the url, so log the (relevant) config?
		}
		return false;
	}
	
	private static boolean createChannel(HashMap<String, Object> config) {
		Connection conn = (Connection) applyDependancy(config, CONNECTION);
		
		if (conn != null) {
			try {
				Channel channel = conn.createChannel();
				
				// https://rabbitmq.github.io/rabbitmq-java-client/api/current/com/rabbitmq/client/Channel.html#basicQos(int)
				int globalPrefetchCount = (int) config.getOrDefault(GLOBAL_QOS, -1);
				int prefetchCount = (int) config.getOrDefault(QOS, -1);

				if (globalPrefetchCount > -1) {
					channel.basicQos(globalPrefetchCount); // channel-wide prefetch count
				}
				
				if (prefetchCount > -1) {
					channel.basicQos(prefetchCount, false); // per consumer prefetch count
				}
				
				config.put(CHANNEL, channel);
				return true;
			} catch (IOException e) {
				log.error("Channel failure", e);
			}
		} else {
			//TODO ?!?!?
		}
		return false;
	}

	private static boolean createExchange(HashMap<String, Object> config) {
		Channel chan = (Channel) applyDependancy(config, CHANNEL);
		String name = (String) config.get(NAME);
		
		if (name == null) {
			log.warn("missing exchange name");
		} else if (chan == null) {
			log.warn("specified channel not available");
		} else {
			try {
				BuiltinExchangeType type = BuiltinExchangeType.valueOf(((String) config.getOrDefault(TYPE, BuiltinExchangeType.DIRECT.getType())).toUpperCase());
				
				Boolean durable = (Boolean) config.getOrDefault(DURABLE, false);
				Boolean autoDelete = (Boolean) config.getOrDefault(AUTO_DELETE, false);
				HashMap<String, Object> options =  (HashMap<String, Object>) config.getOrDefault(OPTIONS, new HashMap<String, Object>());
				
				config.put(EXCHANGE, chan.exchangeDeclare(name, type, durable, autoDelete, options)); //TODO support internal
				return true;
			} catch (IOException e) {
				log.error("Exchange declaration failure", e);
			}
		}
		return false;
	}
	
	private static boolean createQueue(HashMap<String, Object> config) {
		Channel chan = (Channel) applyDependancy(config, CHANNEL);
		String name = (String) config.get(NAME);
		
		if (chan == null) {
			log.warn("specified channel not available");
		} else {
			try {
				Boolean durable = (Boolean) config.getOrDefault(DURABLE, false);
				Boolean autoDelete = (Boolean) config.getOrDefault(AUTO_DELETE, false);
				Boolean exclusive = (Boolean) config.getOrDefault(EXCLUSIVE, false);
				
				HashMap<String, Object> options =  (HashMap<String, Object>) config.getOrDefault(OPTIONS, new HashMap<String, Object>());
						
				config.put(QUEUE, chan.queueDeclare(name, durable, exclusive, autoDelete, options).getQueue());
				return true;
			} catch (IOException e) {
				log.error("Queue declaration failure", e);
			}
		}
		return false;
	}
	
	private static boolean createBinding(HashMap<String, Object> config) {
		HashMap<String, Object> queueConfig = queues.get(config.getOrDefault(QUEUE, DEFAULT));
		HashMap<String, Object> exchangeConfig = exchanges.get(config.getOrDefault(EXCHANGE, DEFAULT));
		
		if (queueConfig == null) {
			log.warn("specified queue not available");
			return false;
		}
		
		if (exchangeConfig == null) {
			log.warn("specified exchange not available");
			return false;
		}
		
		if (queueConfig.get(CHANNEL) != exchangeConfig.get(CHANNEL)) {
			log.warn("queue and exchange not declared on the same channel");
			return false;
		}
		
		Channel chan = (Channel) queueConfig.get(CHANNEL);
		String routingKey = (String) config.get(ROUTING_KEY);
		if (routingKey == null) {
			log.warn("specified routingKey not available");			
		}
		
		try {
			chan.queueBind((String)queueConfig.get(QUEUE), (String)exchangeConfig.get(NAME), routingKey);
			return true;
		} catch (IOException e) {
			log.error("Queue binding failure", e);
		}
		return false;
	}
	
	private static boolean createConsumer(HashMap<String, Object> config) {
		HashMap<String, Object> queueConfig = queues.get(config.getOrDefault(QUEUE, DEFAULT));
		
		if (queueConfig == null) {
			log.warn("Queue not found for Consumer");
			return false;
		}
		
		String handlerValue = (String) config.get(HANDLER);
		URI handler;
		FunctionDefinition fd;
		
		try {
			handler = new URI(handlerValue);
		} catch (URISyntaxException e1) {
			log.warn("Invalid URI {}", handlerValue);
			return false;
		}

		fd = getFunctionDefinition(handler);
		if (fd == null) {
			log.warn("Provided handler value {} does not resolve to a method", handlerValue);
			return false;
		}
		
		Channel chan = (Channel) queueConfig.get(CHANNEL);
		ScriptMethodMessageConsumer consumer = new ScriptMethodMessageConsumer(chan, pool, handler.getScheme(), fd, config);
		
		String consumerTag = (String) config.getOrDefault(CONSUMER_TAG, EMPTY);
		boolean autoAck = (Boolean) config.getOrDefault(AUTO_ACK, true);
		boolean noLocal = (Boolean) config.getOrDefault(NO_LOCAL, false);
		boolean exclusive = (Boolean) config.getOrDefault(EXCLUSIVE, false);
		HashMap<String, Object> options = (HashMap<String, Object>) config.getOrDefault(OPTIONS, new HashMap<String, Object>());

		try {
			chan.basicConsume((String) queueConfig.get(QUEUE), autoAck, consumerTag, noLocal, exclusive, options, consumer);
			return true;
		} catch (Exception e) {
			log.error("Consume failure", e);
		}
		return false;
	}
		
	private static ConnectionFactory getConnectionFactory(HashMap<String, Object> config) {
		//CHECKME instead of building an url string,not better to use settings on the factory? That would also take care of the defaults which are now hardcoded in the string concatenation
		//TODO support url property
		StringBuilder uri = new StringBuilder("amqp://"); //TODO
		
		if (config.containsKey(USER) || config.containsKey(PASSWORD)) {
			String user = (String) config.get(USER);
			String password = (String) config.get(PASSWORD);
			if (user != null && password != null) {
				uri.append(config.get(USER));
				uri.append(":");
				uri.append(config.get(PASSWORD));
				uri.append("@");
			} else {
				log.warn("Incomplete credentials supplied, ignoring them...");
			}
		}
		
		uri.append(config.getOrDefault(HOST, "localhost"));
		uri.append(":");
		uri.append(config.getOrDefault(PORT, "5672"));
		
		if (config.get(VHOST) != null) {
			uri.append("/");
			uri.append(config.get(VHOST));
		}

		
		ConnectionFactory factory = new ConnectionFactory();
		try {
			factory.setUri(uri.toString());
		} catch (KeyManagementException | NoSuchAlgorithmException | URISyntaxException e) {
			log.error("Invalid URI: %s", uri.toString(), e);
			return null;
		}
		
		if (config.get(CONNECTION_TIMEOUT) != null) factory.setConnectionTimeout((int) config.get(CONNECTION_TIMEOUT));
		if (config.get(HANDSHAKE_TIMEOUT) != null) factory.setHandshakeTimeout((int) config.get(HANDSHAKE_TIMEOUT));
		if (config.get(SHUTDOWN_TIMEOUT) != null) factory.setShutdownTimeout((int) config.get(SHUTDOWN_TIMEOUT));
		if (config.get(RPCTIME_OUT) != null) factory.setChannelRpcTimeout((int) config.get(RPCTIME_OUT));
		
		return factory;
	}
	
	private static HeadlessClientPool getPool() {
		if (pool == null) {
			//Init Headless Client Pool
			Integer poolSize = (Integer) globalConfig.get(CLIENT_POOL_SIZE);
			HeadlessClientPool.POOL_EXHAUSTED_ACTIONS exhaustedAction = HeadlessClientPool.POOL_EXHAUSTED_ACTIONS.valueOf(((String)globalConfig.getOrDefault(EXHAUSTED_ACTION,  HeadlessClientPool.POOL_EXHAUSTED_ACTIONS.BLOCK.toString())).toUpperCase());
			
			pool = new HeadlessClientPool(serverAccess, poolSize, exhaustedAction);
		}
		
		return pool;
	}
}
