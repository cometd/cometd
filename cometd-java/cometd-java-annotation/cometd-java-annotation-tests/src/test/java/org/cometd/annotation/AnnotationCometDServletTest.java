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
package org.cometd.annotation;

import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.cometd.annotation.server.AnnotationCometDServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AnnotationCometDServletTest {
    @Test
    public void testLifecycle() throws Exception {
        Server server = new Server();

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        AnnotationCometDServlet cometdServlet = new AnnotationCometDServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        cometdServletHolder.setInitParameter("services", TestService.class.getName());
        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();

        List<Object> services = cometdServlet.getServices();
        Assertions.assertNotNull(services);
        Assertions.assertEquals(1, services.size());

        TestService service = (TestService)services.get(0);
        TestService registeredService = (TestService)context.getServletContext().getAttribute(TestService.class.getName());
        Assertions.assertSame(service, registeredService);

        Assertions.assertTrue(service.init);

        server.stop();
        server.join();

        Assertions.assertTrue(service.destroy);
        Assertions.assertNull(context.getServletContext().getAttribute(TestService.class.getName()));
    }

    @Service("test")
    public static class TestService {
        public boolean init;
        public boolean destroy;

        @PostConstruct
        public void init() {
            init = true;
        }

        @PreDestroy
        public void destroy() {
            destroy = true;
        }
    }
}
