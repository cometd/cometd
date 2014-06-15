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

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.oort.OortConfig;
import org.cometd.oort.OortConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OortAwsConfig extends OortConfig {

	private static final String OORT_AWS_ACCESS_KEY_PARAM = "oort.aws.accessKey";
	private static final String OORT_AWS_SECRET_KEY_PARAM = "oort.aws.secretKey";
	private static final String OORT_AWS_REGION_PARAM = "oort.aws.region";
	private static final String OORT_AWS_FILTERS_PARAM = "oort.aws.filters";
	private static final String OORT_AWS_INSTANCES_REFRESH_INTERVAL_PARAM = "oort.aws.instancesRefreshInterval"; //millis
	private static final String OORT_AWS_CONNECT_TIMEOUT_PARAM = "oort.aws.connectTimeout"; //millis
	private static final String OORT_AWS_RMI_REGISTRY_ADDRESS_PARAM = "oort.aws.rmiRegistryAddress";
	private static final String OORT_AWS_RMI_REGISTRY_PORT_PARAM = "oort.aws.rmiRegistryPort";
	private static final String OORT_AWS_RMI_OBJECTS_PORT_PARAM = "oort.aws.rmiObjectsPort";

	private static final int OORT_AWS_INSTANCES_REFRESH_INTERVAL_DEFAULT = 5000; //millis
	private static final int OORT_AWS_RMI_OBJECTS_PORT_DEFAULT = 40001;
	private static final int OORT_AWS_RMI_REGISTRY_PORT_DEFAULT = 40000;
	private static final int OORT_AWS_CONNECT_TIMEOUT_DEFAULT = 2000;

	private static final Logger _log = LoggerFactory.getLogger(OortAwsConfig.class);

	private OortAwsConfigurer configurer;

	public OortAwsConfig(Properties properties, BayeuxServer bayeux)
			throws OortConfigException {
		super(properties, bayeux);

		String rmiRegistryAddress = properties.getProperty(OORT_AWS_RMI_REGISTRY_ADDRESS_PARAM);
		if (rmiRegistryAddress == null || rmiRegistryAddress.length() == 0) {
			try {
				rmiRegistryAddress = InetAddress.getLocalHost().getHostAddress();
				_log.warn("No registryAddress set. Set to the default of: " + rmiRegistryAddress);
			} catch (UnknownHostException e) {
	        	throw new OortConfigException(e);        	
			}
		}

		String rmiRegistryPortString = properties.getProperty(OORT_AWS_RMI_REGISTRY_PORT_PARAM, String.valueOf(OORT_AWS_RMI_REGISTRY_PORT_DEFAULT));
		int rmiRegistryPort = OORT_AWS_RMI_REGISTRY_PORT_DEFAULT;
		try {
			rmiRegistryPort = Integer.parseInt(rmiRegistryPortString);
		} catch (Exception e) {
			_log.info("No rmiRegistryPort set. Set to the default of: " + rmiRegistryPort);
		}
		
		String rmiObjectsPortString = properties.getProperty(OORT_AWS_RMI_OBJECTS_PORT_PARAM, String.valueOf(OORT_AWS_RMI_OBJECTS_PORT_DEFAULT));
		int rmiObjectsPort = OORT_AWS_RMI_OBJECTS_PORT_DEFAULT;
		try {
			rmiObjectsPort = Integer.parseInt(rmiObjectsPortString);
		} catch (Exception e) {
			_log.info("No rmiObjectsPort set. Set to the default of: " + rmiObjectsPort);
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

		try {
			configurer = new OortAwsConfigurer(rmiRegistryAddress, rmiRegistryPort, rmiObjectsPort, accessKey, secretKey, region, refreshInterval, filtersMap, connectTimeout, oort);
			configurer.start();
		} catch (Exception e) {
			throw new OortConfigException(e);
		}
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
