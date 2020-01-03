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
package org.cometd.oort.spring;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class OortSpringAnnotationTest {
    @Test
    public void testSpringWiringOfOort() throws Exception {
        Server server = new Server();
        ServletContextHandler context = new ServletContextHandler(server, "/");
        JavaxWebSocketServletContainerInitializer.initialize(context);
        context.addEventListener(new ContextLoaderListener());
        context.getInitParams().put(ContextLoader.CONFIG_LOCATION_PARAM, "classpath:/applicationContext.xml");
        server.start();

        ApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(context.getServletContext());
        assertNotNull(applicationContext);

        String serviceClass = OortService.class.getSimpleName();
        String beanName = Character.toLowerCase(serviceClass.charAt(0)) + serviceClass.substring(1);

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        assertTrue(Arrays.asList(beanNames).contains(beanName));

        OortService service = (OortService)applicationContext.getBean(beanName);
        assertNotNull(service);
        Seti seti = service.seti;
        assertNotNull(seti);
        Oort oort = seti.getOort();
        assertNotNull(oort);
        BayeuxServer bayeux = oort.getBayeuxServer();
        assertNotNull(bayeux);

        SecurityPolicy policy = bayeux.getSecurityPolicy();
        assertNotNull(policy);
        assertTrue(policy instanceof OortSecurityPolicy);

        server.stop();
    }
}
