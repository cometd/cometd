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

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OortMulticastConfigurer extends AbstractLifeCycle {
    private static final AtomicInteger ids = new AtomicInteger();

    private final Logger logger;
    private final Oort oort;
    private InetAddress bindAddress;
    private InetAddress groupAddress;
    private List<NetworkInterface> groupInterfaces;
    private int groupPort = 5577;
    private int timeToLive = 1;
    private long advertiseInterval = 2000;
    private long connectTimeout = 2000;
    private int maxTransmissionLength = 1400;
    private Thread receiverThread;
    private Thread senderThread;
    private volatile boolean active;

    public OortMulticastConfigurer(Oort oort) {
        this.logger = LoggerFactory.getLogger(getClass().getName() + "." + Oort.replacePunctuation(oort.getURL(), '_'));
        this.oort = oort;
    }

    /**
     * @return the address to bind the receiver multicast socket to
     */
    public InetAddress getBindAddress() {
        return bindAddress;
    }

    /**
     * @param bindAddress the address to bind the receiver multicast socket to
     */
    public void setBindAddress(InetAddress bindAddress) {
        this.bindAddress = bindAddress;
    }

    /**
     * @return the multicast address onto which Oort URLs are advertised
     */
    public InetAddress getGroupAddress() {
        return groupAddress;
    }

    /**
     * @param groupAddress the multicast address onto which Oort URLs are advertised
     */
    public void setGroupAddress(InetAddress groupAddress) {
        this.groupAddress = groupAddress;
    }

    /**
     * @return the port the receiver multicast socket listens to
     */
    public int getGroupPort() {
        return groupPort;
    }

    /**
     * @param groupPort the port the receiver multicast socket listens to
     */
    public void setGroupPort(int groupPort) {
        this.groupPort = groupPort;
    }

    /**
     * @return the interfaces that receive multicast messages
     */
    public List<NetworkInterface> getGroupInterfaces() {
        return groupInterfaces;
    }

    /**
     * @param groupInterfaces the interfaces that receive multicast messages
     */
    public void setGroupInterfaces(List<NetworkInterface> groupInterfaces) {
        this.groupInterfaces = groupInterfaces;
    }

    /**
     * @return the multicast time-to-live
     */
    public int getTimeToLive() {
        return timeToLive;
    }

    /**
     * @param timeToLive the multicast time-to-live
     */
    public void setTimeToLive(int timeToLive) {
        this.timeToLive = timeToLive;
    }

    /**
     * @return the advertisement interval in milliseconds
     */
    public long getAdvertiseInterval() {
        return advertiseInterval;
    }

    /**
     * @param advertiseInterval the advertisement interval in milliseconds
     */
    public void setAdvertiseInterval(long advertiseInterval) {
        this.advertiseInterval = advertiseInterval;
    }

    /**
     * @return the timeout to connect to another Oort node
     */
    public long getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * @param connectTimeout the timeout to connect to another Oort node
     */
    public void setConnectTimeout(long connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * @return the max Oort URL length (must be smaller than the max transmission unit)
     */
    public int getMaxTransmissionLength() {
        return maxTransmissionLength;
    }

    /**
     * @param maxTransmissionLength the max Oort URL length (must be smaller than the max transmission unit)
     */
    public void setMaxTransmissionLength(int maxTransmissionLength) {
        this.maxTransmissionLength = maxTransmissionLength;
    }

    @Override
    protected void doStart() throws Exception {
        // Bind sender to an ephemeral port and set the TTL
        DatagramChannel sender = DatagramChannel.open();
        sender.setOption(StandardSocketOptions.IP_MULTICAST_TTL, getTimeToLive());

        if (groupAddress == null) {
            groupAddress = InetAddress.getByName("239.255.0.1");
        }

        ProtocolFamily protocolFamily = StandardProtocolFamily.INET;
        if (groupAddress instanceof Inet6Address) {
            protocolFamily = StandardProtocolFamily.INET6;
        }

        // Bind receiver to the given port and bind address
        InetAddress bindTo = getBindAddress();
        InetSocketAddress bindSocketAddress = bindTo == null ? new InetSocketAddress(groupPort) : new InetSocketAddress(bindTo, groupPort);
        DatagramChannel receiver = DatagramChannel.open(protocolFamily)
                .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                .bind(bindSocketAddress);
        if (logger.isDebugEnabled()) {
            logger.debug("Bound multicast receiver to {} with protocol family {}", bindSocketAddress, protocolFamily);
        }

        List<NetworkInterface> groupInterfaces = getGroupInterfaces();
        if (groupInterfaces == null) {
            groupInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        }

        boolean joined = false;
        for (NetworkInterface groupInterface : groupInterfaces) {
            if (logger.isDebugEnabled()) {
                logger.debug("Joining multicast group with {}", groupInterface);
            }
            if (groupInterface.isLoopback() ||
                    groupInterface.isPointToPoint() ||
                    !groupInterface.supportsMulticast() ||
                    !groupInterface.getInetAddresses().hasMoreElements()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Skipped joining multicast group with {}", groupInterface);
                }
                continue;
            }

            try {
                receiver.join(groupAddress, groupInterface);
                if (logger.isDebugEnabled()) {
                    logger.debug("Joined multicast group with {}", groupInterface);
                }
                joined = true;
            } catch (Exception x) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Exception joining multicast group with " + groupInterface, x);
                }
            }
        }
        if (!joined) {
            throw new IOException("Could not join multicast group with " + groupInterfaces);
        }

        active = true;

        senderThread = new Thread(new MulticastSender(sender), "Oort-Multicast-Sender-" + ids.incrementAndGet());
        senderThread.setDaemon(true);
        senderThread.start();

        receiverThread = new Thread(new MulticastReceiver(receiver), "Oort-Multicast-Receiver-" + ids.incrementAndGet());
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    @Override
    protected void doStop() throws Exception {
        active = false;
        senderThread.interrupt();
        receiverThread.interrupt();
    }

    public boolean join(long timeout) {
        try {
            senderThread.join(timeout);
            receiverThread.join(timeout);
            return true;
        } catch (InterruptedException x) {
            return false;
        }
    }

    protected void receive(String cometURL) {
        if (!oort.getKnownComets().contains(cometURL)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received comet URL via multicast: {}", cometURL);
            }
            OortComet oortComet = oort.observeComet(cometURL);
            if (oortComet != null) {
                boolean elapsed = !oortComet.waitFor(getConnectTimeout(), BayeuxClient.State.CONNECTED, BayeuxClient.State.DISCONNECTED);
                // If we could not connect, let's disconnect, we will be advertised again
                if (elapsed) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Interrupting attempts to connect to {}", cometURL);
                    }
                    oort.deobserveComet(cometURL);
                }
            }
        }
    }

    private void close(DatagramChannel channel) {
        try {
            channel.close();
        } catch (IOException x) {
            if (logger.isDebugEnabled()) {
                logger.debug("Could not close " + channel, x);
            }
        }
    }

    private class MulticastReceiver implements Runnable {
        private final DatagramChannel channel;

        public MulticastReceiver(DatagramChannel channel) {
            this.channel = channel;
        }

        @Override
        public void run() {
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("Entering multicast receiver thread on {}", channel.getLocalAddress());
                }

                ByteBuffer buffer = ByteBuffer.allocate(getMaxTransmissionLength());
                String url = null;
                while (active) {
                    if (url != null) {
                        OortMulticastConfigurer.this.receive(url);
                    }
                    url = receive(buffer);
                    // Avoid to process our own URL
                    if (oort.getURL().equals(url)) {
                        url = null;
                    }
                }
            } catch (ClosedByInterruptException x) {
                // Do nothing, we're stopping
            } catch (IOException x) {
                logger.warn("Unexpected exception", x);
            } finally {
                if (logger.isDebugEnabled()) {
                    logger.debug("Exiting multicast receiver thread");
                }
                close(channel);
            }
        }

        private String receive(ByteBuffer buffer) throws IOException {
            buffer.clear();
            channel.receive(buffer);
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }

    private class MulticastSender implements Runnable {
        private final DatagramChannel channel;

        public MulticastSender(DatagramChannel channel) {
            this.channel = channel;
        }

        @Override
        public void run() {
            if (logger.isDebugEnabled()) {
                logger.debug("Entering multicast sender thread");
            }
            try {
                final String cometURL = oort.getURL();
                byte[] cometURLBytes = cometURL.getBytes(StandardCharsets.UTF_8);
                if (cometURLBytes.length > getMaxTransmissionLength()) {
                    logger.warn("Oort URL {} exceeds max transmission unit and will not be advertised", cometURL);
                    return;
                }

                ByteBuffer buffer = ByteBuffer.wrap(cometURLBytes);
                InetSocketAddress address = new InetSocketAddress(getGroupAddress(), getGroupPort());

                while (active) {
                    buffer.clear();
                    channel.send(buffer, address);
                    Thread.sleep(getAdvertiseInterval());
                }
            } catch (InterruptedException | ClosedByInterruptException x) {
                // Do nothing, we're stopping
            } catch (IOException x) {
                logger.warn("Unexpected exception", x);
            } finally {
                if (logger.isDebugEnabled()) {
                    logger.debug("Exiting multicast sender thread");
                }
                close(channel);
            }
        }
    }
}
