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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

import org.cometd.bayeux.server.BayeuxServer;

public class OortMulticastConfig extends OortConfig {

	public static final String OORT_MULTICAST_BIND_ADDRESS_PARAM = "oort.multicast.bindAddress";
	public static final String OORT_MULTICAST_GROUP_ADDRESS_PARAM = "oort.multicast.groupAddress";
	public static final String OORT_MULTICAST_GROUP_PORT_PARAM = "oort.multicast.groupPort";
	public static final String OORT_MULTICAST_TIME_TO_LIVE_PARAM = "oort.multicast.timeToLive";
	public static final String OORT_MULTICAST_ADVERTISE_INTERVAL_PARAM = "oort.multicast.advertiseInterval";
	public static final String OORT_MULTICAST_CONNECT_TIMEOUT_PARAM = "oort.multicast.connectTimeout";
	public static final String OORT_MULTICAST_MAX_TRANSMISSION_LENGTH_PARAM = "oort.multicast.maxTransmissionLength";

	private OortMulticastConfigurer configurer;

	public OortMulticastConfig(Properties properties, BayeuxServer bayeux)
			throws OortConfigException {
		super(properties, bayeux);

		configurer = new OortMulticastConfigurer(oort);

		String bindAddress = properties.getProperty(OORT_MULTICAST_BIND_ADDRESS_PARAM);
		if (bindAddress != null) {
			try {
				configurer.setBindAddress(InetAddress.getByName(bindAddress));
			} catch (UnknownHostException e) {
				throw new OortConfigException(e);
			}
		}
		String groupAddress = properties.getProperty(OORT_MULTICAST_GROUP_ADDRESS_PARAM);
		if (groupAddress != null) {
			try {
				configurer.setGroupAddress(InetAddress.getByName(groupAddress));
			} catch (UnknownHostException e) {
				throw new OortConfigException(e);
			}			
		}
		String groupPort = properties.getProperty(OORT_MULTICAST_GROUP_PORT_PARAM);
		if (groupPort != null)
			configurer.setGroupPort(Integer.parseInt(groupPort));

		String timeToLive = properties.getProperty(OORT_MULTICAST_TIME_TO_LIVE_PARAM);
		if (timeToLive != null)
			configurer.setTimeToLive(Integer.parseInt(timeToLive));

		String advertiseInterval = properties.getProperty(OORT_MULTICAST_ADVERTISE_INTERVAL_PARAM);
		if (advertiseInterval != null)
			configurer.setAdvertiseInterval(Long.parseLong(advertiseInterval));

		String connectTimeout = properties.getProperty(OORT_MULTICAST_CONNECT_TIMEOUT_PARAM);
		if (connectTimeout != null)
			configurer.setConnectTimeout(Long.parseLong(connectTimeout));

		String maxTransmissionLength = properties.getProperty(OORT_MULTICAST_MAX_TRANSMISSION_LENGTH_PARAM);
		if (maxTransmissionLength != null)
			configurer.setMaxTransmissionLength(Integer.parseInt(maxTransmissionLength));

		try {
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
