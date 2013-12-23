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
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OortMulticastConfigurer extends AbstractLifeCycle
{
    private static final AtomicInteger ids = new AtomicInteger();

    private final Logger logger;
    private final Oort oort;
    private InetAddress bindAddress;
    private InetAddress groupAddress;
    private int groupPort = 5577;
    private int timeToLive = 1;
    private long advertiseInterval = 2000;
    private long connectTimeout = 2000;
    private int maxTransmissionLength = 1400;
    private Thread receiverThread;
    private Thread senderThread;
    private volatile boolean active;

    public OortMulticastConfigurer(Oort oort)
    {
        this.logger = LoggerFactory.getLogger(getClass() + "." + Oort.replacePunctuation(oort.getURL(), '_'));
        this.oort = oort;
    }

    /**
     * @return the address to bind the receiver multicast socket to
     */
    public InetAddress getBindAddress()
    {
        return bindAddress;
    }

    /**
     * @param bindAddress the address to bind the receiver multicast socket to
     */
    public void setBindAddress(InetAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    /**
     * @return the multicast address onto which Oort URLs are advertised
     */
    public InetAddress getGroupAddress()
    {
        return groupAddress;
    }

    /**
     * @param groupAddress the multicast address onto which Oort URLs are advertised
     */
    public void setGroupAddress(InetAddress groupAddress)
    {
        this.groupAddress = groupAddress;
    }

    /**
     * @return the port the receiver multicast socket listens to
     */
    public int getGroupPort()
    {
        return groupPort;
    }

    /**
     * @param groupPort the port the receiver multicast socket listens to
     */
    public void setGroupPort(int groupPort)
    {
        this.groupPort = groupPort;
    }

    /**
     * @return the multicast time-to-live
     */
    public int getTimeToLive()
    {
        return timeToLive;
    }

    /**
     * @param timeToLive the multicast time-to-live
     */
    public void setTimeToLive(int timeToLive)
    {
        this.timeToLive = timeToLive;
    }

    /**
     * @return the advertisement interval in milliseconds
     */
    public long getAdvertiseInterval()
    {
        return advertiseInterval;
    }

    /**
     * @param advertiseInterval the advertisement interval in milliseconds
     */
    public void setAdvertiseInterval(long advertiseInterval)
    {
        this.advertiseInterval = advertiseInterval;
    }

    /**
     * @return the timeout to connect to another Oort node
     */
    public long getConnectTimeout()
    {
        return connectTimeout;
    }

    /**
     * @param connectTimeout the timeout to connect to another Oort node
     */
    public void setConnectTimeout(long connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    /**
     * @return the max Oort URL length (must be smaller than the max transmission unit)
     */
    public int getMaxTransmissionLength()
    {
        return maxTransmissionLength;
    }

    /**
     * @param maxTransmissionLength the max Oort URL length (must be smaller than the max transmission unit)
     */
    public void setMaxTransmissionLength(int maxTransmissionLength)
    {
        this.maxTransmissionLength = maxTransmissionLength;
    }

    @Override
    protected void doStart() throws Exception
    {
        // Bind sender to an ephemeral port and set the TTL
        MulticastSocket sender = new MulticastSocket();
        sender.setTimeToLive(getTimeToLive());

        // Bind receiver to the given port and bind address
        InetAddress bindTo = getBindAddress();
        InetSocketAddress bindSocketAddress = bindTo == null ? new InetSocketAddress(groupPort) : new InetSocketAddress(bindTo, groupPort);
        MulticastSocket receiver = new MulticastSocket(bindSocketAddress);
        if (groupAddress == null)
            groupAddress = InetAddress.getByName("239.255.0.1");
        receiver.joinGroup(groupAddress);

        active = true;

        senderThread = new Thread(new MulticastSender(sender), "Oort-Multicast-Sender-" + ids.incrementAndGet());
        senderThread.setDaemon(true);
        senderThread.start();

        receiverThread = new Thread(new MulticastReceiver(receiver), "Oort-Multicast-Receiver-" + ids.incrementAndGet());
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    @Override
    protected void doStop() throws Exception
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
            OortComet oortComet = oort.observeComet(cometURL);
            if (oortComet != null)
            {
                boolean elapsed = !oortComet.waitFor(getConnectTimeout(), BayeuxClient.State.CONNECTED, BayeuxClient.State.DISCONNECTED);
                // If we could not connect, let's disconnect, we will be advertised again
                if (elapsed)
                {
                    logger.debug("Interrupting attempts to connect to {}", cometURL);
                    oort.deobserveComet(cometURL);
                }
            }
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
                // Set a timeout to avoid to wait forever
                // and not notice that we've been stopped.
                socket.setSoTimeout((int)(2 * advertiseInterval));

                byte[] buffer = new byte[getMaxTransmissionLength()];
                String url = null;
                while (active)
                {
                    if (url != null)
                        OortMulticastConfigurer.this.receive(url);
                    url = receive(buffer);
                    // Avoid to process our own URL
                    if (oort.getURL().equals(url))
                        url = null;
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

        private String receive(byte[] buffer) throws IOException
        {
            try
            {
                DatagramPacket packet = new DatagramPacket(buffer, 0, buffer.length);
                socket.receive(packet);
                return new String(buffer, packet.getOffset(), packet.getLength(), "UTF-8");
            }
            catch (SocketTimeoutException x)
            {
                return null;
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
                if (cometURLBytes.length > getMaxTransmissionLength())
                {
                    logger.warn("Oort URL {} exceeds max transmission unit and will not be advertised", cometURL);
                    return;
                }

                while (active)
                {
                    DatagramPacket packet = new DatagramPacket(cometURLBytes, 0, cometURLBytes.length, getGroupAddress(), getGroupPort());
                    socket.send(packet);
                    Thread.sleep(getAdvertiseInterval());
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
