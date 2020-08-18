package com.tod.servoy.plugins.queueing.server;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.servoy.j2db.plugins.IServerAccess;
import com.servoy.j2db.plugins.IServerPlugin;
import com.servoy.j2db.plugins.PluginException;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.Debug;

public class Plugin implements IServerPlugin {
	private int attemptCount = 0;
	
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
					} else if (attemptCount == 120) {
						Debug.error("Failed to initializing plugin: server indicating it hasn't started");
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
				Debug.error("Failure initializing plugin", e);
			}
	}
	
	@Override
	public void unload() throws PluginException {
		//TODO
		//QueueHandler.unload();
	}

	@Override
	public Properties getProperties() {
		//TODO
		return null;
	}

	@Override
	public Map<String, String> getRequiredPropertyNames() {
		return null;
	}
}