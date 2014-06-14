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
package org.cometd.oort;

import java.util.Properties;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.oort.aws.OortAwsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OortConfigFactory {

	private static final Logger logger = LoggerFactory.getLogger(OortConfigFactory.class);
	
	private static final String PEER_DISCOVERY_STATIC = "static";
	private static final String PEER_DISCOVERY_MULTICAST = "multicast";
	private static final String PEER_DISCOVERY_AWS = "aws";
    public final static String OORT_PEER_DISCOVERY_PARAM = "oort.peer_discovery";
	
	public static OortConfig createOortConfigurator(Properties properties, BayeuxServer bayeux) throws OortConfigException {
        String peerDiscoveryType = properties.getProperty(OORT_PEER_DISCOVERY_PARAM, null);
        if (peerDiscoveryType == null) {
    		logger.debug("Missing " + OORT_PEER_DISCOVERY_PARAM + " init parameter");
    		return null;
        }
		
		if(peerDiscoveryType.equals(PEER_DISCOVERY_STATIC)) {
			return new OortStaticConfig(properties, bayeux);
		}
		if(peerDiscoveryType.equals(PEER_DISCOVERY_MULTICAST)) {
			return new OortMulticastConfig(properties, bayeux);
		}
		if(peerDiscoveryType.equals(PEER_DISCOVERY_AWS)) {
			return new OortAwsConfig(properties, bayeux);
		}

		throw new OortConfigException("Not found peerDiscoveryType");
	}
}
