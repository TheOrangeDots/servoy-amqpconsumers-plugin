package com.tod.servoy.plugins.queueing.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties.Builder;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.servoy.j2db.scripting.FunctionDefinition;
import com.servoy.j2db.server.shared.IHeadlessClient;
import com.servoy.j2db.util.serialize.JSONSerializerWrapper;

//TODO figure out (n)acks
public class ScriptMethodMessageConsumer extends DefaultConsumer {
	private static final Logger log = LoggerFactory.getLogger(HeadlessClientPool.class);
	
	private HeadlessClientPool pool;
	private Channel channel;
	private FunctionDefinition handler;
	private String solutionName;
	private static JSONSerializerWrapper serializerWrapper = new JSONSerializerWrapper(false);
	
	public ScriptMethodMessageConsumer(Channel channel, HeadlessClientPool pool, String solutionName, FunctionDefinition handler) {
		super(channel);
		this.pool = pool;
		this.channel = channel;
		this.handler = handler;
		this.solutionName = solutionName;
	}
	
	@Override
	public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
		
		IHeadlessClient client = null;
		try {
			client = pool.getClient(solutionName);
		} catch (Exception e) {
			log.error("failure getting client from pool", e);
		}
		
		if (client != null) {
			if (client.isValid()) {
				byte[] content;
				String contentType = "";
				Object result;
				Exception error = null;
				
				try {
					result = client.getPluginAccess().executeMethod(handler.getContextName(), handler.getMethodName(), new Object[] { consumerTag, envelope, properties, body}, false); //TODO convert envelope and BasicProperties to something easier to use in JavaScript
				} catch (Exception e) {
					log.error("consumer {}://{}/{} failed in handling message delivery", solutionName, handler.getContextName(), handler.getMethodName(), e);
					result = null;
					error = e;
				}
				
				if (properties.getReplyTo() != null) {
					try {
						if (result instanceof JSONObject || result instanceof JSONArray) {
							content = result.toString().getBytes(StandardCharsets.UTF_8);
							contentType = "application/json";
						} else if (result instanceof byte[]) {
							content = (byte[]) result;
						} else {
							content = serializerWrapper.toJSON(result).toString().getBytes(StandardCharsets.UTF_8);
							contentType = "application/json";
						}
						
						Builder replyProps = new AMQP.BasicProperties
	                            .Builder()
	                            .correlationId(properties.getCorrelationId())
	                            .contentType(contentType);
						
						if (error != null) {
							Map<String, Object> headers = new HashMap<String, Object>();
							headers.put("exception", error.getLocalizedMessage());
							replyProps.headers(headers);
						}
						
						channel.basicPublish("", properties.getReplyTo(), replyProps.build(), content);
					} catch (Exception e) {
						log.error("failure sending reply", e);
					}
				}
			}
			pool.releaseClient(solutionName, client, false);
		}	
	}
}