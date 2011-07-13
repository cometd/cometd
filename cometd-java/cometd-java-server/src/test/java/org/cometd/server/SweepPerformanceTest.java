/*
 * Copyright (c) 2011 the original author or authors.
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

import org.junit.Test;

public class SweepPerformanceTest
{
    @Test
    public void testChannelsSweepPerformance()
    {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();
        bayeuxServer.getLogger().setDebugEnabled(Boolean.getBoolean("debugTests"));

        int count = 500000;
        for (int i = 0; i < count; ++i)
        {
            bayeuxServer.createIfAbsent("/" + i);
        }
        System.out.println("Channels created");

        long start = System.nanoTime();
        bayeuxServer.sweep();
        long end = System.nanoTime();

        long elapsed = end - start;
        System.out.println("elapsed = " + elapsed);
    }

    @Test
    public void testSessionsSweepPerformance()
    {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();
        bayeuxServer.getLogger().setDebugEnabled(Boolean.getBoolean("debugTests"));

        int count = 500000;
        for (int i = 0; i < count; ++i)
        {
            bayeuxServer.addServerSession(bayeuxServer.newServerSession());
        }
        System.out.println("Sessions created");

        long start = System.nanoTime();
        bayeuxServer.sweep();
        long end = System.nanoTime();

        long elapsed = end - start;
        System.out.println("elapsed = " + elapsed);
    }
}
