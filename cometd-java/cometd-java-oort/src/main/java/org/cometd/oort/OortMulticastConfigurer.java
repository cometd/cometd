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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Logger;

public class OortMulticastConfigurer
{
    private static final int MTU = 1500;

    private final Oort oort;
    private final Logger logger;
    private InetAddress bindAddress;
    private InetAddress groupAddress;
    private int groupPort = 5577;
    private int timeToLive = 1;
    private long advertiseInterval = 1000;
    private Thread receiverThread;
    private Thread senderThread;
    private volatile boolean active;

    public OortMulticastConfigurer(Oort oort)
    {
        this.oort = oort;
        this.logger = oort.getLogger();
    }

    public void setBindAddress(InetAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public void setGroupAddress(InetAddress groupAddress)
    {
        this.groupAddress = groupAddress;
    }

    public void setGroupPort(int groupPort)
    {
        this.groupPort = groupPort;
    }

    public void setTimeToLive(int timeToLive)
    {
        this.timeToLive = timeToLive;
    }

    public void setAdvertiseInterval(long advertiseInterval)
    {
        this.advertiseInterval = advertiseInterval;
    }

    public void start() throws Exception
    {
        // Bind sender to an ephemeral port and set the TTL
        MulticastSocket sender = new MulticastSocket();
        sender.setTimeToLive(timeToLive);

        // Bind receiver to the given port and bind address
        InetSocketAddress bindSocketAddress = bindAddress == null ? new InetSocketAddress(groupPort) : new InetSocketAddress(bindAddress, groupPort);
        MulticastSocket receiver = new MulticastSocket(bindSocketAddress);
        if (groupAddress == null)
            groupAddress = InetAddress.getByName("239.255.0.1");
        receiver.joinGroup(groupAddress);

        active = true;

        senderThread = new Thread(new MulticastSender(sender), "Oort Multicast Sender");
        senderThread.setDaemon(true);
        senderThread.start();

        receiverThread = new Thread(new MulticastReceiver(receiver), "Oort Multicast Receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    public void stop()
    {
        active = false;
        senderThread.interrupt();
        // We do not interrupt the receiver thread, because it may be processing
        // a received URL and we do not want to get ClosedByInterruptExceptions
    }

    public boolean join(long timeout)
    {
        try
        {
            senderThread.join(timeout);
            receiverThread.join(timeout);
            return true;
        }
        catch (InterruptedException x)
        {
            return false;
        }
    }

    protected void receive(String cometURL)
    {
        if (!oort.getKnownComets().contains(cometURL))
        {
            logger.debug("Received comet URL via multicast: {}", cometURL);
            oort.observeComet(cometURL);
        }
    }

    private class MulticastReceiver implements Runnable
    {
        private final MulticastSocket socket;

        public MulticastReceiver(MulticastSocket socket)
        {
            this.socket = socket;
        }

        public void run()
        {
            logger.debug("Entering multicast receiver thread on {}", socket.getLocalSocketAddress());
            try
            {
                byte[] buffer = new byte[MTU];

                while (active)
                {
                    DatagramPacket packet = new DatagramPacket(buffer, 0, buffer.length);
                    socket.receive(packet);
                    String url = new String(buffer, packet.getOffset(), packet.getLength(), "UTF-8");
                    // Avoid to process our own URL
                    if (!url.equals(oort.getURL()))
                        receive(url);
                }
            }
            catch (IOException x)
            {
                logger.warn("Unexpected exception", x);
            }
            finally
            {
                logger.debug("Exiting multicast receiver thread");
                socket.close();
            }
        }
    }
    private class MulticastSender implements Runnable
    {
        private final MulticastSocket socket;

        public MulticastSender(MulticastSocket socket)
        {
            this.socket = socket;
        }

        public void run()
        {
            logger.debug("Entering multicast sender thread");
            try
            {
                final String cometURL = oort.getURL();
                byte[] cometURLBytes = cometURL.getBytes("UTF-8");
                if (cometURLBytes.length > MTU)
                {
                    logger.info("Oort URL {} exceeds max transmission unit and will not be advertised", cometURL);
                    return;
                }

                while (active)
                {
                    DatagramPacket packet = new DatagramPacket(cometURLBytes, 0, cometURLBytes.length, groupAddress, groupPort);
                    socket.send(packet);

                    TimeUnit.MILLISECONDS.sleep(advertiseInterval);
                }
            }
            catch (IOException x)
            {
                logger.warn("Unexpected exception", x);
            }
            catch (InterruptedException x)
            {
                // Do nothing, we're stopping
            }
            finally
            {
                logger.debug("Exiting multicast sender thread");
                socket.close();
            }
        }
    }
}
