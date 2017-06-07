/*
 * Copyright (c) 2008-2017 the original author or authors.
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
package org.cometd.server;

import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerTransport;
import org.junit.Assert;
import org.junit.Test;

public class SweepTest {
    @Test
    public void testChannelsSweepPerformance() {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();

        StringBuilder builder = new StringBuilder();
        int count = 5000;
        int children = 5;
        for (int i = 0; i < count; ++i) {
            builder.setLength(0);
            for (int j = 0; j < children; ++j) {
                char letter = (char)('a' + j);
                String name = builder.append("/").append(letter).append(i).toString();
                bayeuxServer.createChannelIfAbsent(name);
            }
        }

        long start = System.nanoTime();
        bayeuxServer.sweep();
        long end = System.nanoTime();

        long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(end - start);
        int microsPerSweepPerChannel = 100;
        final int expectedMicros = count * children * microsPerSweepPerChannel;
        Assert.assertTrue("elapsed micros " + elapsedMicros + ", expecting < " + expectedMicros, elapsedMicros < expectedMicros);
    }

    @Test
    public void testChannelsAreSwept() {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();

        StringBuilder builder = new StringBuilder();
        int count = 100;
        int children = 5;
        for (int i = 0; i < count; ++i) {
            builder.setLength(0);
            for (int j = 0; j < children; ++j) {
                char letter = (char)('a' + j);
                String name = builder.append("/").append(letter).append(i).toString();
                bayeuxServer.createChannelIfAbsent(name);
            }
        }

        int sweepPasses = 3;
        int maxIterations = sweepPasses * children * 2;
        int iterations = 0;
        while (bayeuxServer.getChannels().size() > 0) {
            bayeuxServer.sweep();
            ++iterations;
            if (iterations > maxIterations) {
                break;
            }
        }

        Assert.assertEquals(0, bayeuxServer.getChannels().size());
    }

    @Test
    public void testSessionsSweepPerformance() {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();

        int count = 25000;
        for (int i = 0; i < count; ++i) {
            bayeuxServer.addServerSession(bayeuxServer.newServerSession(), bayeuxServer.newMessage());
        }

        long start = System.nanoTime();
        bayeuxServer.sweep();
        long end = System.nanoTime();

        long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(end - start);
        int microsPerSweepPerChannel = 100;
        final int expectedMicros = count * microsPerSweepPerChannel;
        Assert.assertTrue("elapsed micros " + elapsedMicros + ", expecting < " + expectedMicros, elapsedMicros < expectedMicros);
    }

    @Test
    public void testLocalSessionIsNotSwept() throws Exception {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();
        bayeuxServer.setOption(BayeuxServerImpl.SWEEP_PERIOD_OPTION, -1);
        long maxInterval = 1000;
        bayeuxServer.setOption("maxInterval", maxInterval);
        bayeuxServer.setOption("maxProcessing", maxInterval);
        bayeuxServer.start();
        ServerTransport serverTransport = bayeuxServer.getTransport("long-polling");
        bayeuxServer.setCurrentTransport(serverTransport);

        // LocalSessions do not perform heartbeat so we should not sweep them until disconnected
        LocalSession localSession = bayeuxServer.newLocalSession("test_sweep");
        localSession.handshake();

        bayeuxServer.sweep();

        Assert.assertNotNull(bayeuxServer.getSession(localSession.getId()));

        Thread.sleep(maxInterval * 2);

        bayeuxServer.sweep();

        Assert.assertNotNull(bayeuxServer.getSession(localSession.getId()));

        localSession.disconnect();
    }
}
