package com.tod.servoy.plugins.queueing.server;

import java.util.NoSuchElementException;

import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.servoy.j2db.plugins.IServerAccess;
import com.servoy.j2db.server.headlessclient.HeadlessClientFactory;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.server.shared.IHeadlessClient;
import com.servoy.j2db.util.Debug;
import com.servoy.j2db.util.Utils;

//TODO create PR to put this in Servoy codebase, so any plugin can use it
public class HeadlessClientPool {
	private static final Logger log = LoggerFactory.getLogger(HeadlessClientPool.class);
	private static final int CLIENT_POOL_SIZE_DEFAULT = 5;

	private GenericKeyedObjectPool<String, IHeadlessClient> clientPool;
	private IServerAccess access;
	
	private int poolSize;
	private POOL_EXHAUSTED_ACTIONS exhaustedAction;
	private static final String[] SOLUTION_OPEN_METHOD_ARGS = new String[] { "rest_ws_server" };
	
	public enum POOL_EXHAUSTED_ACTIONS {
		GROW,
		BLOCK,
		FAIL
	};

	//TODO named pools that can be shared between different plugins
	//TODO SOLUTION_OPEN_METHOD_ARGS
	//TODO what about solutions that require authentication?
//	public HeadlessClientPool(IServerAccess access, Integer poolSize, POOL_EXHAUSTED_ACTIONS exhaustedAction) {
//		HeadlessClientPool(access, poolSize, exhaustedAction, new Object[]);
//	}
	
	public HeadlessClientPool(IServerAccess access, Integer poolSize, POOL_EXHAUSTED_ACTIONS exhaustedAction) {
		this.access = access;
		this.poolSize = (poolSize == null || poolSize <= 1) ? CLIENT_POOL_SIZE_DEFAULT : poolSize.intValue();
		this.exhaustedAction = exhaustedAction == null ? POOL_EXHAUSTED_ACTIONS.BLOCK : exhaustedAction;
	}

	private synchronized KeyedObjectPool<String, IHeadlessClient> getClientPool()
	{
		if (clientPool != null) {
			return clientPool;
		}
			
		GenericKeyedObjectPoolConfig config = new GenericKeyedObjectPoolConfig();

		if (ApplicationServerRegistry.get().isDeveloperStartup()) {
			// in developer multiple clients do not work well with debugger
			config.setBlockWhenExhausted(true);
			config.setMaxTotal(1);
		} else {
			config.setMaxTotal(poolSize);

			if (exhaustedAction == POOL_EXHAUSTED_ACTIONS.FAIL) {
				config.setBlockWhenExhausted(false);
				log.debug("Client pool, exchaustedAction={}", POOL_EXHAUSTED_ACTIONS.FAIL.toString());
			}
			else if (exhaustedAction == POOL_EXHAUSTED_ACTIONS.GROW) {
				config.setMaxTotal(-1);
				log.debug("Client pool, exchaustedAction={}", POOL_EXHAUSTED_ACTIONS.GROW.toString());
			}
			else { // stick to defaults
				log.debug("Client pool, exchaustedAction={}", POOL_EXHAUSTED_ACTIONS.BLOCK.toString());
			}
		}

		log.debug("Creating client pool, maxSize={}", config.getMaxTotal());

		clientPool = new GenericKeyedObjectPool<>(new BaseKeyedPooledObjectFactory<String, IHeadlessClient>()
		{
			@Override
			public IHeadlessClient create(String key) throws Exception
			{
				log.debug("creating new session client for solution '{}'", key);
				String solutionName = key;
				String[] solOpenArgs = SOLUTION_OPEN_METHOD_ARGS;

				String[] arr = solutionName.split(":");
				if (arr.length == 2)
				{
					solutionName = arr[0];
					solOpenArgs = Utils.arrayJoin(SOLUTION_OPEN_METHOD_ARGS, new String[] { "nodebug" });
				}
				IHeadlessClient client =  HeadlessClientFactory.createHeadlessClient(solutionName, solOpenArgs);
				if (!client.isValid()) {
					throw new IllegalStateException("Created Headless Client is invalid");
				} else if (client.getPluginAccess().getSolutionName() == null) {
					String message = "Created Headless Client doesn't have the specified solution %s loaded";
					if (client.getPluginAccess().isInDeveloper()) { //TODO if in Developer and the solution is null AND nodebug is not set, retry with nodebug and if successfull log a warning
						message += ". Is the solution (part of) the Active Solution?";
					}
					throw new IllegalStateException(String.format(message, key));
				}
				return client;
			}

			@Override
			public PooledObject<IHeadlessClient> wrap(IHeadlessClient value)
			{
				return new DefaultPooledObject<>(value);
			}

			@Override
			public boolean validateObject(String key, PooledObject<IHeadlessClient> pooledObject)
			{
				IHeadlessClient client = pooledObject.getObject();
				if (client.getPluginAccess().isInDeveloper())
				{
					String solutionName = key;
					if (solutionName.contains(":")) {
						solutionName = solutionName.split(":")[0];
					}
					
					if (!solutionName.equals(client.getPluginAccess().getSolutionName()))
					{
						try
						{
							client.closeSolution(true);
							client.loadSolution(solutionName);
						}
						catch (Exception ex)
						{
							return false;
						}
					}
				}
				boolean valid = client.isValid();
				log.debug("Validated session client for solution '{}', valid = {}", key, valid);
				return valid;
			}

			@Override
			public void destroyObject(String key, PooledObject<IHeadlessClient> pooledObject) throws Exception
			{
				log.debug("Destroying session client for solution '{}'", key);
				IHeadlessClient client = pooledObject.getObject();
				try
				{
					client.shutDown(true);
				}
				catch (Exception e)
				{
					Debug.error(e);
				}
			}
		});
		clientPool.setConfig(config);
		clientPool.setTestOnBorrow(true);
		
		return clientPool;
	}

	public IHeadlessClient getClient(String solutionName) throws Exception
	{
		try
		{
			return getClientPool().borrowObject(solutionName);
		}
		catch (NoSuchElementException e)
		{
			// no more licenses
			throw new NoClientsException();
		}
	}

	public void releaseClient(final String poolKey, final IHeadlessClient client, boolean reloadSolution)
	{
		if (reloadSolution)
		{
			access.getExecutor().execute(new Runnable()
			{
				@Override
				public void run()
				{
					boolean solutionReopened = false;
					try
					{
						if (client.isValid()) {
							client.closeSolution(true);
							String[] arr = poolKey.split(":");
							client.loadSolution(arr.length == 2 ? arr[0] : poolKey); // avoid the ":nodebug" part from the pool key...
							solutionReopened = true;
						}
					}
					catch (Exception ex)
					{
						log.error("cannot reopen solution '{}'", poolKey, ex);
						client.shutDown(true);
					}
					finally
					{
						try
						{
							if (solutionReopened) {
								getClientPool().returnObject(poolKey, client);
							} else {
								getClientPool().invalidateObject(poolKey, client);
							}
						}
						catch (Exception ex)
						{
							log.error("Failure releasing client back to pool", ex);
						}
					}
				}
			});
		}
		else
		{
			// This is potentially dangerous, only reuse clients with loaded solution if you are very sure the client did not keep state!
			try
			{
				getClientPool().returnObject(poolKey, client);
			}
			catch (Exception ex)
			{
				log.error("Exception returning borrowed Headless CLient to pool", ex);
			}
		}
	}

	public static class NoClientsException extends Exception
	{
		private static final long serialVersionUID = 1L;
	}

	//CHECKME figure out what to do with these...
//	public static class NotAuthorizedException extends Exception
//	{
//		public NotAuthorizedException(String message)
//		{
//			super(message);
//		}
//	}
//
//	public static class NotAuthenticatedException extends Exception
//	{
//		private final String realm;
//
//		public NotAuthenticatedException(String realm)
//		{
//			this.realm = realm;
//		}
//
//		public String getRealm()
//		{
//			return realm;
//		}
//	}
//
//	public static class ExecFailedException extends Exception
//	{
//		public ExecFailedException(Exception e)
//		{
//			super(e);
//		}
//
//		@Override
//		public Exception getCause()
//		{
//			return (Exception)super.getCause();
//		}
//
//		public boolean isUserScriptException()
//		{
//			return getCause() instanceof JavaScriptException;
//		}
//	}
}
