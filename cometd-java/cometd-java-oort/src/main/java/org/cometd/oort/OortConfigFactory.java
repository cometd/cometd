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

import org.cometd.oort.aws.OortAwsConfig;

public class OortConfigFactory {

	private static final String PEER_DISCOVERY_STATIC = "static";
	private static final String PEER_DISCOVERY_MULTICAST = "multicast";
	private static final String PEER_DISCOVERY_AWS = "aws";
	
	public static OortConfig createOortConfigurator(String peerDiscoveryType) throws OortConfigException {
		if(peerDiscoveryType == null) {
			throw new OortConfigException("peerDiscoveryType is null");
		}
		
		if(peerDiscoveryType.equals(PEER_DISCOVERY_STATIC)) {
			return new OortStaticConfig();
		}
		if(peerDiscoveryType.equals(PEER_DISCOVERY_MULTICAST)) {
			return new OortMulticastConfig();
		}
		if(peerDiscoveryType.equals(PEER_DISCOVERY_AWS)) {
			return new OortAwsConfig();
		}

		throw new OortConfigException("Not found peerDiscoveryType");
	}
}
