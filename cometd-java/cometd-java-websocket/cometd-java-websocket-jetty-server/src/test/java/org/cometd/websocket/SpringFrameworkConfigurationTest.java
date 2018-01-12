/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.websocket;

import org.cometd.bayeux.server.ServerTransport;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.BayeuxServerImpl;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class SpringFrameworkConfigurationTest extends ClientServerWebSocketTest {
    public SpringFrameworkConfigurationTest(String implementation) {
        super(implementation);
    }

    @Test
    public void testXMLSpringConfigurationWithWebSocket() throws Exception {
        prepareServer(0, null, false);
        // Add Spring listener
        context.addEventListener(new ContextLoaderListener());
        String config = WEBSOCKET_JSR_356.equals(wsTransportType) ?
                "applicationContext-javax-websocket.xml" : "applicationContext-jetty-websocket.xml";
        context.getInitParams().put(ContextLoader.CONFIG_LOCATION_PARAM, "classpath:/" + config);
        startServer();

        prepareClient();
        startClient();

        WebApplicationContext applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(context.getServletContext());
        Assert.assertNotNull(applicationContext);

        BayeuxServerImpl bayeuxServer = (BayeuxServerImpl)applicationContext.getBean("bayeux");
        Assert.assertNotNull(bayeuxServer);
        Assert.assertTrue(bayeuxServer.isStarted());

        Assert.assertSame(bayeuxServer, bayeux);

        ServerTransport transport = bayeuxServer.getTransport("websocket");
        Assert.assertNotNull(transport);

        final BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for connect to establish
        Thread.sleep(1000);

        ClientTransport clientTransport = client.getTransport();
        Assert.assertEquals("websocket", clientTransport.getName());

        disconnectBayeuxClient(client);
    }
}
