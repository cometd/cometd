/*
 * Copyright (c) 2008-2022 the original author or authors.
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
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class OortServiceTest extends AbstractOortObjectTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testActionIsForwarded(Transport transport) throws Exception {
        prepare(transport);

        CountDownLatch latch1 = new CountDownLatch(1);
        Service service1 = new Service(oort1, latch1);
        service1.start();
        CountDownLatch latch2 = new CountDownLatch(1);
        Service service2 = new Service(oort2, latch2);
        service2.start();

        Assertions.assertTrue(service1.perform(oort2.getURL(), oort1.getURL()));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(service1.result);
        Assertions.assertNull(service1.failure);

        Assertions.assertTrue(service2.perform(oort2.getURL(), oort2.getURL()));
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(service2.result);
        Assertions.assertNull(service2.failure);

        service2.stop();
        service1.stop();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testActionIsNotForwardedForUnknownURL(Transport transport) throws Exception {
        prepare(transport);

        Service service1 = new Service(oort1, null);
        service1.start();

        Assertions.assertFalse(service1.perform("unknownURL", "context1"));

        service1.stop();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testActionIsBroadcast(Transport transport) throws Exception {
        prepare(transport);

        CountDownLatch latch1 = new CountDownLatch(1);
        BroadcastService service1 = new BroadcastService(oort1, latch1);
        CountDownLatch latch2 = new CountDownLatch(1);
        BroadcastService service2 = new BroadcastService(oort2, latch2);
        String channelName = service1.getBroadcastChannelName();
        CometSubscriptionListener listener1 = new CometSubscriptionListener(channelName, 1);
        oort1.getBayeuxServer().addListener(listener1);
        CometSubscriptionListener listener2 = new CometSubscriptionListener(channelName, 1);
        oort2.getBayeuxServer().addListener(listener2);
        service1.start();
        // Wait for node1 to be subscribed on node2
        Assertions.assertTrue(listener2.await(5, TimeUnit.SECONDS));
        service2.start();
        // Wait for node2 to be subscribed on node1
        Assertions.assertTrue(listener1.await(5, TimeUnit.SECONDS));

        // Broadcast to all services, only one will reply
        String replier = oort2.getURL();
        Assertions.assertTrue(service1.perform(null, replier, "context"));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(service1.result);
        Assertions.assertNull(service1.failure);

        // Send to the wrong service, ignore must be converted to failure
        String target = oort1.getURL();
        replier = oort2.getURL();
        Assertions.assertTrue(service2.perform(target, replier, "context"));
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assertions.assertNull(service2.result);
        Assertions.assertNotNull(service2.failure);

        service2.stop();
        service1.stop();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testActionFailsOnRuntimeException(Transport transport) throws Exception {
        prepare(transport);

        CountDownLatch latch1 = new CountDownLatch(1);
        Service service1 = new Service(oort1, latch1) {
            @Override
            protected Result<Boolean> onForward(Request request) {
                throw new NullPointerException();
            }
        };
        service1.start();
        CountDownLatch latch2 = new CountDownLatch(1);
        Service service2 = new Service(oort2, latch2) {
            @Override
            protected Result<Boolean> onForward(Request request) {
                throw new NullPointerException();
            }
        };
        service2.start();

        Assertions.assertTrue(service1.perform(oort2.getURL(), oort1.getURL()));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        Assertions.assertNull(service1.result);
        Assertions.assertEquals(NullPointerException.class.getName(), service1.failure);

        Assertions.assertTrue(service2.perform(oort2.getURL(), oort2.getURL()));
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assertions.assertNull(service2.result);
        Assertions.assertEquals(NullPointerException.class.getName(), service2.failure);

        service2.stop();
        service1.stop();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testActionFailsOnFailure(Transport transport) throws Exception {
        prepare(transport);

        String failure = "failure";
        CountDownLatch latch1 = new CountDownLatch(1);
        Service service1 = new Service(oort1, latch1) {
            @Override
            protected Result<Boolean> onForward(Request request) {
                return Result.failure(failure);
            }
        };
        service1.start();
        CountDownLatch latch2 = new CountDownLatch(1);
        Service service2 = new Service(oort2, latch2) {
            @Override
            protected Result<Boolean> onForward(Request request) {
                return Result.failure(failure);
            }
        };
        service2.start();

        Assertions.assertTrue(service1.perform(oort2.getURL(), oort1.getURL()));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        Assertions.assertNull(service1.result);
        Assertions.assertEquals(failure, service1.failure);

        Assertions.assertTrue(service2.perform(oort2.getURL(), oort2.getURL()));
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assertions.assertNull(service2.result);
        Assertions.assertEquals(failure, service2.failure);

        service2.stop();
        service1.stop();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testActionBroadcastTimeout(Transport transport) throws Exception {
        prepare(transport);

        long timeout = 1000;
        CountDownLatch latch1 = new CountDownLatch(1);
        ExpireService service1 = new ExpireService(oort1, latch1) {
            @Override
            protected Result<Void> onForward(Request request) {
                return Result.ignore(null);
            }
        };
        service1.setTimeout(timeout);
        CountDownLatch latch2 = new CountDownLatch(1);
        ExpireService service2 = new ExpireService(oort2, latch2) {
            @Override
            protected Result<Void> onForward(Request request) {
                return Result.ignore(null);
            }
        };
        service2.setTimeout(timeout);
        String channelName = service1.getBroadcastChannelName();
        CometSubscriptionListener listener1 = new CometSubscriptionListener(channelName, 1);
        oort1.getBayeuxServer().addListener(listener1);
        CometSubscriptionListener listener2 = new CometSubscriptionListener(channelName, 1);
        oort2.getBayeuxServer().addListener(listener2);
        service1.start();
        // Wait for node1 to be subscribed on node2
        Assertions.assertTrue(listener2.await(5, TimeUnit.SECONDS));
        service2.start();
        // Wait for node2 to be subscribed on node1
        Assertions.assertTrue(listener1.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(service1.perform(null));
        Assertions.assertTrue(latch1.await(2 * timeout, TimeUnit.MILLISECONDS));

        Assertions.assertTrue(service2.perform(null));
        Assertions.assertTrue(latch2.await(2 * timeout, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testActionForwardTimeout(Transport transport) throws Exception {
        prepare(transport);

        long timeout = 1000;
        CountDownLatch latch1 = new CountDownLatch(1);
        ExpireService service1 = new ExpireService(oort1, latch1) {
            @Override
            protected Result<Void> onForward(Request request) {
                try {
                    TimeUnit.MILLISECONDS.sleep(2 * timeout);
                    return Result.success(null);
                } catch (InterruptedException x) {
                    return Result.failure(null);
                }
            }
        };
        service1.setTimeout(timeout);
        service1.start();
        CountDownLatch latch2 = new CountDownLatch(1);
        ExpireService service2 = new ExpireService(oort2, latch2) {
            @Override
            protected Result<Void> onForward(Request request) {
                try {
                    TimeUnit.MILLISECONDS.sleep(2 * timeout);
                    return Result.success(null);
                } catch (InterruptedException x) {
                    return Result.failure(null);
                }
            }
        };
        service2.setTimeout(timeout);
        service2.start();

        Assertions.assertTrue(service1.perform(oort2.getURL()));
        Assertions.assertTrue(latch1.await(3 * timeout, TimeUnit.MILLISECONDS));

        TimeUnit.MILLISECONDS.sleep(2 * timeout);

        Assertions.assertTrue(service2.perform(oort2.getURL()));
        Assertions.assertTrue(latch2.await(3 * timeout, TimeUnit.MILLISECONDS));

        // Allow last perform to complete without exceptions
        TimeUnit.MILLISECONDS.sleep(2 * timeout);
    }

    private static class Service extends OortService<Boolean, String> {
        private final CountDownLatch latch;
        private volatile String context;
        private volatile Boolean result;
        private volatile Object failure;

        private Service(Oort oort, CountDownLatch latch) {
            super(oort, "test");
            this.latch = latch;
        }

        public boolean perform(String oortURL, String context) {
            this.context = context;
            return forward(oortURL, oortURL, context);
        }

        @Override
        protected Result<Boolean> onForward(Request request) {
            if (request.isLocal()) {
                Assertions.assertEquals(context, request.getOortURL());
            }
            return Result.success(getOort().getURL().equals(request.getData()));
        }

        @Override
        protected void onForwardSucceeded(Boolean result, String context) {
            Assertions.assertSame(this.context, context);
            this.result = result;
            latch.countDown();
        }

        @Override
        protected void onForwardFailed(Object failure, String context) {
            Assertions.assertSame(this.context, context);
            this.failure = failure;
            latch.countDown();
        }
    }

    private static class BroadcastService extends OortService<Boolean, String> {
        private final CountDownLatch latch;
        private volatile String context;
        private volatile Boolean result;
        private volatile Object failure;

        private BroadcastService(Oort oort, CountDownLatch latch) {
            super(oort, "test");
            this.latch = latch;
        }

        public String getBroadcastChannelName() {
            return "/oort/service/" + getName();
        }

        public boolean perform(String oortURL, String parameter, String context) {
            this.context = context;
            return forward(oortURL, parameter, context);
        }

        @Override
        protected Result<Boolean> onForward(Request request) {
            if (!request.isLocal() && getOort().getURL().equals(request.getData())) {
                return Result.success(true);
            } else {
                return Result.ignore(false);
            }
        }

        @Override
        protected void onForwardSucceeded(Boolean result, String context) {
            Assertions.assertSame(this.context, context);
            this.result = result;
            latch.countDown();
        }

        @Override
        protected void onForwardFailed(Object failure, String context) {
            Assertions.assertSame(this.context, context);
            this.failure = failure;
            latch.countDown();
        }
    }

    private abstract static class ExpireService extends OortService<Void, Void> {
        private final CountDownLatch latch;

        private ExpireService(Oort oort, CountDownLatch latch) {
            super(oort, "test");
            this.latch = latch;
        }

        public String getBroadcastChannelName() {
            return "/oort/service/" + getName();
        }

        public boolean perform(String oortURL) {
            return forward(oortURL, null, null);
        }

        @Override
        protected void onForwardSucceeded(Void result, Void context) {
        }

        @Override
        protected void onForwardFailed(Object failure, Void context) {
            if (failure instanceof TimeoutException) {
                latch.countDown();
            }
        }
    }
}
