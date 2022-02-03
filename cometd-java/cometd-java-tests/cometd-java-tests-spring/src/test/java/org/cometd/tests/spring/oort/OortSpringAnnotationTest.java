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
package org.cometd.tests.spring.oort;

import java.util.List;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class OortSpringAnnotationTest {
    @Test
    public void testSpringWiringOfOort() throws Exception {
        Server server = new Server();
        ServletContextHandler context = new ServletContextHandler(server, "/");
        JavaxWebSocketServletContainerInitializer.configure(context, null);
        context.addEventListener(new ContextLoaderListener());
        context.getInitParams().put(ContextLoader.CONFIG_LOCATION_PARAM, "classpath:/applicationContext-oort.xml");
        server.start();

        ApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(context.getServletContext());
        Assertions.assertNotNull(applicationContext);

        String serviceClass = OortService.class.getSimpleName();
        String beanName = Character.toLowerCase(serviceClass.charAt(0)) + serviceClass.substring(1);

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        Assertions.assertTrue(List.of(beanNames).contains(beanName));

        OortService service = (OortService)applicationContext.getBean(beanName);
        Assertions.assertNotNull(service);
        Seti seti = service.seti;
        Assertions.assertNotNull(seti);
        Oort oort = seti.getOort();
        Assertions.assertNotNull(oort);
        BayeuxServer bayeux = oort.getBayeuxServer();
        Assertions.assertNotNull(bayeux);

        SecurityPolicy policy = bayeux.getSecurityPolicy();
        Assertions.assertNotNull(policy);
        Assertions.assertTrue(policy instanceof OortSecurityPolicy);

        server.stop();
    }
}
