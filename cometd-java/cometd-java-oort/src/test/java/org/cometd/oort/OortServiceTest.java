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

import org.junit.Assert;
import org.junit.Test;

public class OortServiceTest extends AbstractOortObjectTest
{
    @Test
    public void testActionIsForwarded() throws Exception
    {
        CountDownLatch latch1 = new CountDownLatch(1);
        Service service1 = new Service(oort1, latch1);
        service1.start();
        CountDownLatch latch2 = new CountDownLatch(1);
        Service service2 = new Service(oort2, latch2);
        service2.start();

        service1.perform(oort2.getURL(), "context1");
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(service1.result);
        Assert.assertNull(service1.failure);

        service2.perform(oort2.getURL(), "context2");
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(service2.result);
        Assert.assertNull(service2.failure);

        service2.stop();
        service1.stop();
    }

    @Test
    public void testActionFailsOnRuntimeException() throws Exception
    {
        CountDownLatch latch1 = new CountDownLatch(1);
        Service service1 = new Service(oort1, latch1)
        {
            @Override
            protected Boolean onForward(Object actionData)
            {
                throw new NullPointerException();
            }
        };
        service1.start();
        CountDownLatch latch2 = new CountDownLatch(1);
        Service service2 = new Service(oort2, latch2)
        {
            @Override
            protected Boolean onForward(Object actionData)
            {
                throw new NullPointerException();
            }
        };
        service2.start();

        service1.perform(oort2.getURL(), "context1");
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        Assert.assertNull(service1.result);
        Assert.assertEquals(NullPointerException.class.getName(), service1.failure);

        service2.perform(oort2.getURL(), "context2");
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assert.assertNull(service2.result);
        Assert.assertEquals(NullPointerException.class.getName(), service2.failure);

        service2.stop();
        service1.stop();
    }

    @Test
    public void testActionFailsOnServiceException() throws Exception
    {
        final String failure = "failure";
        CountDownLatch latch1 = new CountDownLatch(1);
        Service service1 = new Service(oort1, latch1)
        {
            @Override
            protected Boolean onForward(Object actionData)
            {
                throw new ServiceException(failure);
            }
        };
        service1.start();
        CountDownLatch latch2 = new CountDownLatch(1);
        Service service2 = new Service(oort2, latch2)
        {
            @Override
            protected Boolean onForward(Object actionData)
            {
                throw new ServiceException(failure);
            }
        };
        service2.start();

        service1.perform(oort2.getURL(), "context1");
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        Assert.assertNull(service1.result);
        Assert.assertEquals(failure, service1.failure);

        service2.perform(oort2.getURL(), "context2");
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assert.assertNull(service2.result);
        Assert.assertEquals(failure, service2.failure);

        service2.stop();
        service1.stop();
    }

    private static class Service extends OortService<Boolean, String>
    {
        private final CountDownLatch latch;
        private volatile String context;
        private volatile Boolean result;
        private volatile Object failure;

        private Service(Oort oort, CountDownLatch latch)
        {
            super(oort, "test");
            this.latch = latch;
        }

        @Override
        protected String getLoggerName()
        {
            return OortService.class.getName();
        }

        public void perform(String oortURL, String context)
        {
            this.context = context;
            forward(oortURL, oortURL, context);
        }

        @Override
        protected Boolean onForward(Object actionData)
        {
            return getOort().getURL().equals(actionData);
        }

        @Override
        protected void onForwardSucceeded(Boolean result, String context)
        {
            Assert.assertSame(this.context, context);
            this.result = result;
            latch.countDown();
        }

        @Override
        protected void onForwardFailed(Object failure, String context)
        {
            Assert.assertSame(this.context, context);
            this.failure = failure;
            latch.countDown();
        }
    }
}
