package com.tod.servoy.plugins.queueing.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.servoy.j2db.plugins.IServerAccess;
import com.servoy.j2db.plugins.IServerPlugin;
import com.servoy.j2db.plugins.PluginException;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.Debug;

public class Plugin implements IServerPlugin {
	private final int defaultMaxInitAttempts = 5*60;
	private final int maxInitAttempts = Integer.parseInt(System.getProperty(ConfigHandler.PROPERT_KEY_PREFIX + "maxInitDelay", Integer.toString(defaultMaxInitAttempts)));
	private int attemptCount = 0;
	private final String name = "AMQP Listeners";
	
	@Override
	public void load() throws PluginException {}

	@Override
	public void initialize(final IServerAccess access) throws PluginException {
			int initialDelay = ApplicationServerRegistry.get().isDeveloperStartup() ? 30 : 0;
			
			Runnable runner = new Runnable() {
				@Override
				public void run() {
					if (!ApplicationServerRegistry.get().isStarting()) {
						ConfigHandler.init(access);
					} else if (attemptCount == maxInitAttempts) {
						Debug.error("Failed to initializing " + name + "plugin: server indicating it hasn't started after ~" + (attemptCount + initialDelay) + " seconds");
					} else {
						attemptCount++;

						access.getExecutor().schedule(this, 1, TimeUnit.SECONDS);
					}
				}
			};
			
			//Delayed init as the plugin is getting loaded during the server startup and the plugin needs solutions available
			try {
				access.getExecutor().schedule(runner, initialDelay, TimeUnit.SECONDS);				
			} catch (Exception e) {
				Debug.error("Failure initializing " + name + " plugin", e);
			}
	}
	
	@Override
	public void unload() throws PluginException {
		//TODO
		//QueueHandler.unload();
	}

	@Override
	public Properties getProperties() {
		Properties props = new Properties();
		
		props.putAll(Map.of(
				DISPLAY_NAME, name,
				DISPLAY_ADMINPAGE_URL, "https://github.com/TheOrangeDots/servoy-amqplisteners-plugin"
		));
		
		return props;
	}

	@Override
	public Map<String, String> getRequiredPropertyNames() {
		Map<String, String> props = new LinkedHashMap<String, String>();
		
		props.put(ConfigHandler.PROPERT_KEY_PREFIX + "maxInitDelay",
			"# of seconds to attempt to initialize the plugin based on the provided config when the server is starting. Default: " + Integer.toString(defaultMaxInitAttempts) + " seconds");
		
		return props;
	}
}