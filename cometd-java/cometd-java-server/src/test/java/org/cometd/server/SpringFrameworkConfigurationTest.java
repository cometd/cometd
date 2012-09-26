/*
 * Copyright (c) 2010 the original author or authors.
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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// TODO
@Ignore("Test fails because in Jetty 9 servlets are eagerly initialized (while before they were lazily initialized)" +
        "so the Spring context listener runs after the CometD servlet, causing the test to fail")
public class SpringFrameworkConfigurationTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testXMLSpringConfiguration() throws Exception
    {
        int port = this.port;
        server.stop();
        // Add Spring listener
        context.addEventListener(new ContextLoaderListener());
        context.getInitParams().put(ContextLoader.CONFIG_LOCATION_PARAM, "classpath:/applicationContext.xml");
        connector.setPort(port);
        server.start();

        WebApplicationContext applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(context.getServletContext());
        assertNotNull(applicationContext);

        String logLevel = (String)applicationContext.getBean("logLevel");

        BayeuxServerImpl bayeuxServer = (BayeuxServerImpl)applicationContext.getBean("bayeux");
        assertNotNull(bayeuxServer);
        assertTrue(bayeuxServer.isStarted());
        assertEquals(logLevel, bayeuxServer.getOption("logLevel"));

        assertSame(bayeuxServer, cometdServlet.getBayeux());

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        assertEquals(200, response.status());
    }
}
