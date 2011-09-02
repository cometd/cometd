/*
 * Copyright (c) 2010 the original author or authors.
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
 * <li><code>oort.multicast.bindAddress</code>, to specify the bind address of the
 * {@link MulticastSocket} that receives the advertisements; defaults to the wildcard
 * address</li>
 * <li><code>oort.multicast.groupAddress</code>, to specify the multicast group address
 * to join to receive the advertisements; defaults to 239.255.0.1</li>
 * <li><code>oort.multicast.groupPort</code>, to specify the port over which advertisements
 * are sent and received; defaults to 5577</li>
 * <li><code>oort.multicast.timeToLive</code>, to specify the time to live of advertisement packets;
 * defaults to 1 (1 = same subnet, 32 = same site, 255 = global)</li>
 * <li><code>oort.multicast.advertiseInterval</code>, to specify the interval in milliseconds
 * at which advertisements are sent; defaults to 1000 ms</li>
 * </ul>
 *
 * @see OortConfigServlet
 * @see OortMulticastConfigServlet
 */
public class OortMulticastConfigServlet extends OortConfigServlet
{
    public static final String OORT_MULTICAST_BIND_ADDRESS_PARAM = "oort.multicast.bindAddress";
    public static final String OORT_MULTICAST_GROUP_ADDRESS_PARAM = "oort.multicast.groupAddress";
    public static final String OORT_MULTICAST_GROUP_PORT_PARAM = "oort.multicast.groupPort";
    public static final String OORT_MULTICAST_TIME_TO_LIVE_PARAM = "oort.multicast.timeToLive";
    public static final String OORT_MULTICAST_ADVERTISE_INTERVAL_PARAM = "oort.multicast.advertiseInterval";

    private OortMulticastConfigurer configurer;

    @Override
    protected void configureCloud(ServletConfig config, Oort oort) throws Exception
    {
        configurer = new OortMulticastConfigurer(oort);

        String bindAddress = config.getInitParameter(OORT_MULTICAST_BIND_ADDRESS_PARAM);
        if (bindAddress != null)
            configurer.setBindAddress(InetAddress.getByName(bindAddress));

        String groupAddress = config.getInitParameter(OORT_MULTICAST_GROUP_ADDRESS_PARAM);
        if (groupAddress != null)
            configurer.setGroupAddress(InetAddress.getByName(groupAddress));

        String groupPort = config.getInitParameter(OORT_MULTICAST_GROUP_PORT_PARAM);
        if (groupPort != null)
            configurer.setGroupPort(Integer.parseInt(groupPort));

        String timeToLive = config.getInitParameter(OORT_MULTICAST_TIME_TO_LIVE_PARAM);
        if (timeToLive != null)
            configurer.setTimeToLive(Integer.parseInt(timeToLive));

        String advertiseInterval = config.getInitParameter(OORT_MULTICAST_ADVERTISE_INTERVAL_PARAM);
        if (advertiseInterval != null)
            configurer.setAdvertiseInterval(Long.parseLong(advertiseInterval));

        configurer.start();
    }

    @Override
    public void destroy()
    {
        if (configurer != null)
        {
            configurer.stop();
            configurer.join(1000);
        }
        super.destroy();
    }
}
