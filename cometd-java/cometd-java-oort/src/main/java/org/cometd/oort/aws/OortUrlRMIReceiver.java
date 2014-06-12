/*
 * Copyright (c) 2008-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.oort.aws;

import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;

import org.cometd.client.BayeuxClient;
import org.cometd.oort.Oort;
import org.cometd.oort.OortComet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OortUrlRMIReceiver extends UnicastRemoteObject implements OortUrlRMIReceiverIF {

    private final Logger logger;

	private Oort oort;
	private final String rmiPeerAddress;
	private final int rmiPeerPort;
	private final long connectTimeout;
	private Registry registry;
	private boolean registryCreated;
	
	public OortUrlRMIReceiver(String rmiPeerAddress, int rmiPeerPort, long connectTimeout, Oort oort) throws RemoteException, MalformedURLException  {
		this.logger = LoggerFactory.getLogger(getClass() + "." + Oort.replacePunctuation(oort.getURL(), '_'));

		this.oort = oort;
		this.rmiPeerAddress = rmiPeerAddress;
		this.rmiPeerPort = rmiPeerPort;
		this.connectTimeout = connectTimeout;
		
		startRegistry();
		Naming.rebind(getUrl(), this);
		
        if(logger.isDebugEnabled()) {
        	logger.debug("Created OortUrlReceiver [peerAddress: " + rmiPeerAddress + ", peerPort: " + rmiPeerPort + "]");        	
        }
	}
		
    public final String getUrl() {
        return new StringBuilder()
                .append("//")
                .append(rmiPeerAddress)
                .append(":")
                .append(rmiPeerPort)
                .append("/")
                .append(this.getClass().getName())
                .toString();
    }
	
	public void registerCometUrl(String cometURL) throws RemoteException {
        if (!oort.getKnownComets().contains(cometURL))
        {
            logger.debug("Received comet URL via RMI: {}", cometURL);
            OortComet oortComet = oort.observeComet(cometURL);
            if (oortComet != null)
            {
                boolean elapsed = !oortComet.waitFor(connectTimeout, BayeuxClient.State.CONNECTED, BayeuxClient.State.DISCONNECTED);
                // If we could not connect, let's disconnect, we will be advertised again
                if (elapsed)
                {
                    logger.debug("Interrupting attempts to connect to {}", cometURL);
                    oort.deobserveComet(cometURL);
                }
            }
        }
	}

	public void dispose() {
		try {
			Naming.unbind(getUrl());
			stopRegistry();
		} catch (Exception e) {
			logger.warn("Exception unbinding on disposing.", e);
		}
	}
	
	/**
	 * Start the rmiregistry.
	 * <p/>
	 * The alternative is to use the <code>rmiregistry</code> binary, in which case:
	 * <ol/>
	 * <li>rmiregistry running
	 * <li>-Djava.rmi.server.codebase="file:///Users/gluck/work/ehcache/build/classes/ file:///Users/gluck/work/ehcache/lib/commons-logging-1.0.4.jar"
	 * </ol>
	 *
	 * @throws RemoteException
	 */
	protected void startRegistry() throws RemoteException {
		try {
			registry = LocateRegistry.getRegistry(rmiPeerPort);
			try {
				registry.list();
			} catch (RemoteException e) {
				//may not be created. Let's create it.
				registry = LocateRegistry.createRegistry(rmiPeerPort);
				registryCreated = true;
			}
		} catch (ExportException exception) {
			logger.error("Exception starting RMI registry. Error was " + exception.getMessage(), exception);
		}
	}

	/**
	 * Stop the rmiregistry if it was started by this class.
	 *
	 * @throws RemoteException
	 */
	protected void stopRegistry() throws RemoteException {
		if (registryCreated) {
			// the unexportObject call must be done on the Registry object returned
			// by createRegistry not by getRegistry, a NoSuchObjectException is
			// thrown otherwise
			boolean success = UnicastRemoteObject.unexportObject(registry, true);
			if (success) {
				logger.debug("rmiregistry unexported.");
			} else {
				logger.warn("Could not unexport rmiregistry.");
			}
		}
	}
}
