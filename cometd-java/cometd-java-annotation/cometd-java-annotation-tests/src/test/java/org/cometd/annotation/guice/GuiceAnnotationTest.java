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
package org.cometd.annotation.guice;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.server.BayeuxServerImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
        Assertions.assertTrue(processor.process(service));

        // At this point we're configured properly
        // The code above should be put into a ServletContextListener.contextInitialized()
        // method, so that it is triggered by the web application lifecycle handling
        // and the BayeuxServer instance can be put into the ServletContext

        // Test that we're configured properly
        Assertions.assertNotNull(service);
        Assertions.assertNotNull(service.dependency);
        Assertions.assertNotNull(service.bayeuxServer);
        Assertions.assertNotNull(service.serverSession);
        Assertions.assertTrue(service.active);
        Assertions.assertEquals(1, service.bayeuxServer.getChannel(GuiceBayeuxService.CHANNEL).getSubscribers().size());

        // Deconfigure services
        // The code below should be put into a ServletContextListener.contextDestroyed()
        // method, so that it is triggered by the web application lifecycle handling
        Assertions.assertTrue(processor.deprocess(service));
        // Manually stop the BayeuxServer
        bayeuxServer.stop();

        Assertions.assertFalse(service.active);
    }
}
