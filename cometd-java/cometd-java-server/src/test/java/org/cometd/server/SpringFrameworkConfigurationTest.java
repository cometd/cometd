/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Before;
import org.junit.Test;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SpringFrameworkConfigurationTest extends AbstractBayeuxClientServerTest {
    public SpringFrameworkConfigurationTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testXMLSpringConfiguration() throws Exception {
        int port = this.port;
        server.stop();
        // Add Spring listener
        context.addEventListener(new ContextLoaderListener());
        context.getInitParams().put(ContextLoader.CONFIG_LOCATION_PARAM, "classpath:/applicationContext.xml");
        connector.setPort(port);
        server.start();

        WebApplicationContext applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(context.getServletContext());
        assertNotNull(applicationContext);

        int sweepPeriod = (Integer)applicationContext.getBean("sweepPeriod");

        BayeuxServerImpl bayeuxServer = (BayeuxServerImpl)applicationContext.getBean("bayeux");
        assertNotNull(bayeuxServer);
        assertTrue(bayeuxServer.isStarted());
        assertEquals(sweepPeriod, bayeuxServer.getOption("sweepPeriod"));

        assertSame(bayeuxServer, cometdServlet.getBayeux());

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        assertEquals(200, response.getStatus());
    }
}
