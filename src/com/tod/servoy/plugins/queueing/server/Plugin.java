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
	
	@Override
	public void load() throws PluginException {}

	@Override
	public void initialize(final IServerAccess access) throws PluginException {
			int delay = ApplicationServerRegistry.get().isDeveloperStartup() ? 30 : 0;
		
			//Delayed init as the plugin is getting loaded during the server startup
			try {
				access.getExecutor().schedule(new Runnable() {
					
					@Override
					public void run() {
						if (ApplicationServerRegistry.waitForApplicationServerStarted()) {
							ConfigHandler.init(access);
						}
					}
					
				}, delay, TimeUnit.SECONDS);
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