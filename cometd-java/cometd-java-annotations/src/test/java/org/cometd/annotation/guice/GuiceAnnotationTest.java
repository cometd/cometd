/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.annotation.guice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.cometd.annotation.ServerAnnotationProcessor;
import org.cometd.server.BayeuxServerImpl;
import org.junit.Assert;
import org.junit.Test;

public class GuiceAnnotationTest {
    @Test
    public void testGuiceWiringOfCometDServices() throws Exception {
        // Configure Guice
        Injector injector = Guice.createInjector(new CometDModule());
        // Manually start BayeuxServer
        BayeuxServerImpl bayeuxServer = injector.getInstance(BayeuxServerImpl.class);
        bayeuxServer.start();

        // Configure services
        // Guice does not handle @PostConstruct and @PreDestroy, so we need to handle them
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeuxServer);
        GuiceBayeuxService service = injector.getInstance(GuiceBayeuxService.class);
        Assert.assertTrue(processor.process(service));

        // At this point we're configured properly
        // The code above should be put into a ServletContextListener.contextInitialized()
        // method, so that it is triggered by the web application lifecycle handling
        // and the BayeuxServer instance can be put into the ServletContext

        // Test that we're configured properly
        Assert.assertNotNull(service);
        assertNotNull(service.dependency);
        assertNotNull(service.bayeuxServer);
        assertNotNull(service.serverSession);
        assertTrue(service.active);
        assertEquals(1, service.bayeuxServer.getChannel(GuiceBayeuxService.CHANNEL).getSubscribers().size());

        // Deconfigure services
        // The code below should be put into a ServletContextListener.contextDestroyed()
        // method, so that it is triggered by the web application lifecycle handling
        Assert.assertTrue(processor.deprocess(service));
        // Manually stop the BayeuxServer
        bayeuxServer.stop();

        assertFalse(service.active);
    }
}
