/*
 * Copyright (c) 2013 the original author or authors.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OortMasterCounterTest extends OortTest
{
    private Oort oort1;
    private Oort oort2;

    @Before
    public void prepare() throws Exception
    {
        Server server1 = startServer(0);
        oort1 = startOort(server1);
        Server server2 = startServer(0);
        oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertNotNull(oortComet21);
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));
    }

    @Test
    public void testCount() throws Exception
    {
        String name = "test";
        final long initial = 3;
        OortMasterService.Chooser chooser = new OortMasterService.Chooser()
        {
            public boolean choose(OortMasterService service)
            {
                boolean master = oort1.getURL().equals(service.getOort().getURL());
                if (master)
                    ((OortMasterCounter)service).setInitialValue(initial);
                return master;
            }
        };

        OortMasterCounter counter1 = new OortMasterCounter(oort1, name, chooser);
        counter1.start();
        OortMasterCounter counter2 = new OortMasterCounter(oort2, name, chooser);
        counter2.start();

        // Wait for the nodes to synchronize
        Thread.sleep(1000);

        final CountDownLatch latch1 = new CountDownLatch(1);
        Assert.assertTrue(counter1.get(new OortMasterCounter.Callback.Adapter()
        {
            @Override
            public void succeeded(Long result)
            {
                Assert.assertEquals(initial, (long)result);
                latch1.countDown();
            }
        }));
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assert.assertEquals(initial, (long)counter1.onForward(0));

        final CountDownLatch latch2 = new CountDownLatch(1);
        Assert.assertTrue(counter2.get(new OortMasterCounter.Callback.Adapter()
        {
            @Override
            public void succeeded(Long result)
            {
                Assert.assertEquals(initial, (long)result);
                latch2.countDown();
            }
        }));
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assert.assertEquals(0, (long)counter2.onForward(0));

        final CountDownLatch latch3 = new CountDownLatch(1);
        Assert.assertTrue(counter1.addAndGet(1, new OortMasterCounter.Callback.Adapter()
        {
            @Override
            public void succeeded(Long result)
            {
                Assert.assertEquals(initial + 1, (long)result);
                latch3.countDown();
            }
        }));
        Assert.assertTrue(latch3.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assert.assertEquals(initial + 1, (long)counter1.onForward(0));

        final CountDownLatch latch4 = new CountDownLatch(1);
        Assert.assertTrue(counter2.addAndGet(1, new OortMasterCounter.Callback.Adapter()
        {
            @Override
            public void succeeded(Long result)
            {
                Assert.assertEquals(initial + 2, (long)result);
                latch4.countDown();
            }
        }));
        Assert.assertTrue(latch4.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assert.assertEquals(0, (long)counter2.onForward(0));

        final CountDownLatch latch5 = new CountDownLatch(1);
        Assert.assertTrue(counter2.getAndAdd(1, new OortMasterCounter.Callback.Adapter()
        {
            @Override
            public void succeeded(Long result)
            {
                Assert.assertEquals(initial + 2, (long)result);
                latch5.countDown();
            }
        }));
        Assert.assertTrue(latch5.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assert.assertEquals(0, (long)counter2.onForward(0));

        final CountDownLatch latch6 = new CountDownLatch(1);
        Assert.assertTrue(counter1.get(new OortMasterCounter.Callback.Adapter()
        {
            @Override
            public void succeeded(Long result)
            {
                Assert.assertEquals(initial + 3, (long)result);
                latch6.countDown();
            }
        }));
        Assert.assertTrue(latch6.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assert.assertEquals(initial + 3, (long)counter1.onForward(0));
    }
}
