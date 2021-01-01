/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletConfig;
import org.cometd.bayeux.server.BayeuxServer;

/**
 * <p>This servlet initializes and configures an instance of the {@link Oort}
 * CometD cluster manager via autodiscovery of other Oort comets using UDP multicast.</p>
 * <p>This servlet configures and starts an instance of {@link OortMulticastConfigurer}
 * that advertises via multicast the Oort URL to which it is associated and
 * receives the advertisements from other Oort comets.</p>
 * <p>This servlet must be initialized after an instance the CometD servlet
 * that creates the {@link BayeuxServer} instance used by {@link Oort}.</p>
 * <p>This servlet inherits from {@link OortConfigServlet} init parameters used
 * to configure the Oort instance, and adds the following init parameters:</p>
 * <ul>
 * <li>{@code oort.multicast.bindAddress}, to specify the bind address of the
 * {@link MulticastSocket} that receives the advertisements; defaults to the wildcard
 * address</li>
 * <li>{@code oort.multicast.groupAddress}, to specify the multicast group address
 * to join to receive the advertisements; defaults to 239.255.0.1</li>
 * <li>{@code oort.multicast.groupPort}, to specify the port over which advertisements
 * are sent and received; defaults to 5577</li>
 * <li>{@code oort.multicast.groupInterfaces}, a comma separated list of IP addresses
 * that will join the multicast group; default to all interfaces that support multicast</li>
 * <li>{@code oort.multicast.timeToLive}, to specify the time to live of advertisement packets;
 * defaults to 1 (1 = same subnet, 32 = same site, 255 = global)</li>
 * <li>{@code oort.multicast.advertiseInterval}, to specify the interval in milliseconds
 * at which advertisements are sent; defaults to 2000 ms</li>
 * <li>{@code oort.multicast.connectTimeout}, to specify the timeout in milliseconds
 * that a node should wait to connect to another node; defaults to 2000 ms</li>
 * <li>{@code oort.multicast.maxTransmissionLength}, to specify the maximum length in bytes
 * of the advertisement message, and should be smaller than the max transmission unit;
 * defaults to 1400 bytes</li>
 * </ul>
 *
 * @see OortConfigServlet
 * @see OortMulticastConfigServlet
 */
public class OortMulticastConfigServlet extends OortConfigServlet {
    public static final String OORT_MULTICAST_BIND_ADDRESS_PARAM = "oort.multicast.bindAddress";
    public static final String OORT_MULTICAST_GROUP_ADDRESS_PARAM = "oort.multicast.groupAddress";
    public static final String OORT_MULTICAST_GROUP_PORT_PARAM = "oort.multicast.groupPort";
    public static final String OORT_MULTICAST_GROUP_INTERFACES_PARAM = "oort.multicast.groupInterfaces";
    public static final String OORT_MULTICAST_TIME_TO_LIVE_PARAM = "oort.multicast.timeToLive";
    public static final String OORT_MULTICAST_ADVERTISE_INTERVAL_PARAM = "oort.multicast.advertiseInterval";
    public static final String OORT_MULTICAST_CONNECT_TIMEOUT_PARAM = "oort.multicast.connectTimeout";
    public static final String OORT_MULTICAST_MAX_TRANSMISSION_LENGTH_PARAM = "oort.multicast.maxTransmissionLength";

    private OortMulticastConfigurer configurer;

    @Override
    protected void configureCloud(ServletConfig config, Oort oort) throws Exception {
        configurer = new OortMulticastConfigurer(oort);

        String bindAddress = config.getInitParameter(OORT_MULTICAST_BIND_ADDRESS_PARAM);
        if (bindAddress != null) {
            configurer.setBindAddress(InetAddress.getByName(bindAddress));
        }

        String groupAddress = config.getInitParameter(OORT_MULTICAST_GROUP_ADDRESS_PARAM);
        if (groupAddress != null) {
            configurer.setGroupAddress(InetAddress.getByName(groupAddress));
        }

        String groupPort = config.getInitParameter(OORT_MULTICAST_GROUP_PORT_PARAM);
        if (groupPort != null) {
            configurer.setGroupPort(Integer.parseInt(groupPort));
        }

        String groupInterfaceList = config.getInitParameter(OORT_MULTICAST_GROUP_INTERFACES_PARAM);
        if (groupInterfaceList != null) {
            List<NetworkInterface> networkInterfaces = new ArrayList<>();
            String[] groupInterfaces = groupInterfaceList.split(",");
            for (String groupInterface : groupInterfaces) {
                groupInterface = groupInterface.trim();
                if (!groupInterface.isEmpty()) {
                    networkInterfaces.add(NetworkInterface.getByInetAddress(InetAddress.getByName(groupInterface)));
                }
            }
            configurer.setGroupInterfaces(networkInterfaces);
        }

        String timeToLive = config.getInitParameter(OORT_MULTICAST_TIME_TO_LIVE_PARAM);
        if (timeToLive != null) {
            configurer.setTimeToLive(Integer.parseInt(timeToLive));
        }

        String advertiseInterval = config.getInitParameter(OORT_MULTICAST_ADVERTISE_INTERVAL_PARAM);
        if (advertiseInterval != null) {
            configurer.setAdvertiseInterval(Long.parseLong(advertiseInterval));
        }

        String connectTimeout = config.getInitParameter(OORT_MULTICAST_CONNECT_TIMEOUT_PARAM);
        if (connectTimeout != null) {
            configurer.setConnectTimeout(Long.parseLong(connectTimeout));
        }

        String maxTransmissionLength = config.getInitParameter(OORT_MULTICAST_MAX_TRANSMISSION_LENGTH_PARAM);
        if (maxTransmissionLength != null) {
            configurer.setMaxTransmissionLength(Integer.parseInt(maxTransmissionLength));
        }

        configurer.start();
    }

    @Override
    public void destroy() {
        if (configurer != null) {
            stopConfigurer();
            configurer.join(1000);
        }
        super.destroy();
    }

    private void stopConfigurer() {
        try {
            configurer.stop();
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }
}
