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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.cometd.oort.Oort;
import org.cometd.oort.OortConfig;
import org.cometd.oort.OortConfigException;


public class OortAwsConfig implements OortConfig {

	private static final String OORT_AWS_ACCESS_KEY_PARAM = "oort.aws.accessKey";
	private static final String OORT_AWS_SECRET_KEY_PARAM = "oort.aws.secretKey";
	private static final String OORT_AWS_REGION_PARAM = "oort.aws.region";
	private static final String OORT_AWS_FILTERS_PARAM = "oort.aws.filters";
	private static final String OORT_AWS_INSTANCES_REFRESH_INTERVAL_PARAM = "oort.aws.instancesRefreshInterval"; //millis
	private static final String OORT_AWS_CONNECT_TIMEOUT_PARAM = "oort.aws.connectTimeout"; //millis
	private static final String OORT_AWS_RMI_PEER_ADDRESS_PARAM = "oort.aws.rmiPeerAddress";
	private static final String OORT_AWS_RMI_PEER_REMOTE_PORT_PARAM = "oort.aws.rmiRemotePeerPort";
	private static final String OORT_AWS_RMI_PEER_PORT_PARAM = "oort.aws.rmiPeerPort";

	private static final int OORT_AWS_INSTANCES_REFRESH_INTERVAL_DEFAULT = 5000; //millis
	private static final int OORT_AWS_RMI_PEER_PORT_DEFAULT = 40000;
	private static final int OORT_AWS_CONNECT_TIMEOUT_DEFAULT = 2000;

	private final Logger _log = LoggerFactory.getLogger(OortAwsConfig.class);

	private OortAwsConfigurer configurer;

	@Override
	public void configureCloud(Properties properties, Oort oort) throws OortConfigException {
		String rmiPeerAddress = properties.getProperty(OORT_AWS_RMI_PEER_ADDRESS_PARAM);
		if (rmiPeerAddress == null || rmiPeerAddress.length() == 0) {
			try {
				rmiPeerAddress = InetAddress.getLocalHost().getHostAddress();
				_log.warn("No peerAddress set. Set to the default of: " + rmiPeerAddress);
			} catch (UnknownHostException e) {
	        	throw new OortConfigException(e);        	
			}
		}

		String rmiPeerPortString = properties.getProperty(OORT_AWS_RMI_PEER_PORT_PARAM, String.valueOf(OORT_AWS_RMI_PEER_PORT_DEFAULT));
		int rmiPeerPort = OORT_AWS_RMI_PEER_PORT_DEFAULT;
		try {
			rmiPeerPort = Integer.parseInt(rmiPeerPortString);
		} catch (Exception e) {
			_log.info("No peerPort set. Set to the default of: " + rmiPeerPort);
		}
		
		String rmiRemotePeerPortString = properties.getProperty(OORT_AWS_RMI_PEER_REMOTE_PORT_PARAM, String.valueOf(rmiPeerPort));
		int rmiRemotePeerPort = OORT_AWS_RMI_PEER_PORT_DEFAULT;
		try {
			rmiRemotePeerPort = Integer.parseInt(rmiRemotePeerPortString);
		} catch (Exception e) {
			_log.info("No peerPort set. Set to the default of: " + rmiRemotePeerPort);
		}

		String accessKey = properties.getProperty(OORT_AWS_ACCESS_KEY_PARAM);
        if (accessKey == null) {
        	throw new OortConfigException("Missing " + OORT_AWS_ACCESS_KEY_PARAM + " parameter");        	
        }
		String secretKey = properties.getProperty(OORT_AWS_SECRET_KEY_PARAM);
        if (secretKey == null) {
        	throw new OortConfigException("Missing " + OORT_AWS_SECRET_KEY_PARAM + " parameter");        	
        }
		String region = properties.getProperty(OORT_AWS_REGION_PARAM);
        if (region == null) {
        	throw new OortConfigException("Missing " + OORT_AWS_REGION_PARAM + " parameter");        	
        }
        
        int refreshInterval = OORT_AWS_INSTANCES_REFRESH_INTERVAL_DEFAULT;
		String refreshIntervalString = properties.getProperty(OORT_AWS_INSTANCES_REFRESH_INTERVAL_PARAM);
		if(refreshIntervalString != null) {
			try {
				refreshInterval = Integer.valueOf(refreshIntervalString);
			} catch(Exception e) {
				_log.warn("Error parsing refresh interval", e);
			}			
		}

		String filters = properties.getProperty(OORT_AWS_FILTERS_PARAM);
		HashMap<String, List<String>> filtersMap = new HashMap<String, List<String>>();
		if(filters != null) {
			StringTokenizer tokenizer = new StringTokenizer(filters, ";");
			while (tokenizer.hasMoreTokens()) {
					String filter = tokenizer.nextToken();
					String[] keyAndValues = filter.split("=");
					ArrayList<String> values = new ArrayList<String>();
					values.add(keyAndValues[1]);
					filtersMap.put(keyAndValues[0], values);
			}
		}

		String connectTimeoutString = properties.getProperty(OORT_AWS_CONNECT_TIMEOUT_PARAM, "" + OORT_AWS_CONNECT_TIMEOUT_DEFAULT);
		long connectTimeout = Long.parseLong(connectTimeoutString);

		configurer = new OortAwsConfigurer(rmiPeerAddress, rmiPeerPort, rmiRemotePeerPort, accessKey, secretKey, region, refreshInterval, filtersMap, connectTimeout, oort);
	}

	@Override
	public void destroyCloud() throws OortConfigException {
		if(configurer == null) {
			return;
		}
		try {
			stopConfigurer();
		} catch (Exception e) {
			throw new OortConfigException(e);
		}
		configurer.join(1000);
	}

	private void stopConfigurer() throws Exception
	{
		configurer.stop();
	}

}
