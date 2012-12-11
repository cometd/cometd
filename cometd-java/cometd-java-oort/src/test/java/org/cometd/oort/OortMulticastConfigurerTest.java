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

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class OortMulticastConfigurerTest extends OortTest
{
    private final List<OortMulticastConfigurer> configurers = new ArrayList<>();

    @Before
    public void assumeMulticast() throws Exception
    {
        InetAddress multicastAddress = InetAddress.getByName("239.255.0.1");

        // Make sure receiver is setup before the sender, otherwise the packet gets lost
        MulticastSocket receiver = new MulticastSocket(0);
        receiver.joinGroup(multicastAddress);

        MulticastSocket sender = new MulticastSocket();
        sender.setTimeToLive(1);
        sender.send(new DatagramPacket(new byte[]{1}, 0, 1, multicastAddress, receiver.getLocalPort()));
        sender.close();

        byte[] buffer = new byte[1];
        receiver.setSoTimeout(1000);
        try
        {
            receiver.receive(new DatagramPacket(buffer, 0, buffer.length));
        }
        catch (SocketTimeoutException x)
        {
            Assume.assumeNoException(x);
        }
        finally
        {
            receiver.close();
        }
    }

    private OortMulticastConfigurer startConfigurer(Oort oort, int groupPort) throws Exception
    {
        OortMulticastConfigurer configurer = new OortMulticastConfigurer(oort);
        configurer.setGroupPort(groupPort);
        configurer.start();
        configurers.add(configurer);
        return configurer;
    }

    @After
    public void stopConfigurers()
    {
        for (int i = configurers.size() - 1; i >= 0; --i)
            stopConfigurer(configurers.get(i));
    }

    private void stopConfigurer(OortMulticastConfigurer configurer)
    {
        configurer.stop();
        configurer.join(1000);
    }

    @Test
    public void testTwoComets() throws Exception
    {
        Server server1 = startServer(0);
        int groupPort = ((NetworkConnector)server1.getConnectors()[0]).getLocalPort();
        Oort oort1 = startOort(server1);
        startConfigurer(oort1, groupPort);

        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);
        startConfigurer(oort2, groupPort);

        // Give some time to advertise
        Thread.sleep(1000);

        Assert.assertEquals(1, oort1.getKnownComets().size());
        Assert.assertEquals(1, oort2.getKnownComets().size());
    }

    @Test
    public void testThreeComets() throws Exception
    {
        Server server1 = startServer(0);
        int groupPort = ((NetworkConnector)server1.getConnectors()[0]).getLocalPort();
        Oort oort1 = startOort(server1);
        startConfigurer(oort1, groupPort);

        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);
        final OortMulticastConfigurer configurer2 = startConfigurer(oort2, groupPort);

        // Give some time to advertise
        Thread.sleep(1000);

        Assert.assertEquals(1, oort1.getKnownComets().size());
        Assert.assertEquals(1, oort2.getKnownComets().size());

        // Create another comet
        Server server3 = startServer(0);
        Oort oort3 = startOort(server3);
        startConfigurer(oort3, groupPort);

        // Give some time to advertise
        Thread.sleep(1000);

        Assert.assertEquals(2, oort1.getKnownComets().size());
        Assert.assertEquals(2, oort2.getKnownComets().size());
        Assert.assertEquals(2, oort3.getKnownComets().size());

        stopConfigurer(configurer2);
        stopOort(oort2);
        stopServer(server2);

        // Give some time to advertise
        Thread.sleep(1000);

        Assert.assertEquals(1, oort1.getKnownComets().size());
        Assert.assertEquals(oort3.getURL(), oort1.getKnownComets().iterator().next());
        Assert.assertEquals(1, oort3.getKnownComets().size());
        Assert.assertEquals(oort1.getURL(), oort3.getKnownComets().iterator().next());
    }
}
