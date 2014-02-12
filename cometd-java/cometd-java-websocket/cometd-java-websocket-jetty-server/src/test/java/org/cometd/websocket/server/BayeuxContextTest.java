/*
 * Copyright (c) 2008-2014 the original author or authors.
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
package org.cometd.websocket.server;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.websocket.ClientServerWebSocketTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.junit.Assert;
import org.junit.Test;

public class BayeuxContextTest extends ClientServerWebSocketTest
{
    public BayeuxContextTest(String implementation)
    {
        super(implementation);
    }

    @Test
    public void testRequestHeaderIsCaseInsensitive() throws Exception
    {
        prepareAndStart(null);

        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                BayeuxContext context = bayeux.getContext();
                Assert.assertEquals(context.getHeader("Host"), context.getHeader("HOST"));
                Assert.assertEquals(context.getHeader("Host"), context.getHeaderValues("HOST").get(0));
                latch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testCookiesSentToServer() throws Exception
    {
        prepareAndStart(null);

        final String cookieName = "name";
        final String cookieValue = "value";
        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                BayeuxContext context = bayeux.getContext();
                Assert.assertEquals(cookieValue, context.getCookie(cookieName));
                latch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.putCookie(new HttpCookie(cookieName, cookieValue));
        client.handshake();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testCookiesSentToClient() throws Exception
    {
        String wsTransportClass;
        switch (wsTransportType)
        {
            case WEBSOCKET_JSR_356:
                wsTransportClass = CookieWebSocketTransport.class.getName();
                break;
            case WEBSOCKET_JETTY:
                wsTransportClass = CookieJettyWebSocketTransport.class.getName();
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareServer(0, null, true, wsTransportClass);
        startServer();
        prepareClient();
        startClient();

        BayeuxClient client = newBayeuxClient();
        client.handshake();

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        HttpCookie cookie = client.getCookie(CookieConstants.COOKIE_NAME);
        Assert.assertEquals(CookieConstants.COOKIE_VALUE, cookie.getValue());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSessionAttribute() throws Exception
    {
        String wsTransportClass;
        switch (wsTransportType)
        {
            case WEBSOCKET_JSR_356:
                wsTransportClass = SessionWebSocketTransport.class.getName();
                break;
            case WEBSOCKET_JETTY:
                wsTransportClass = SessionJettyWebSocketTransport.class.getName();
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareServer(0, null, true, wsTransportClass);

        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp) throws ServletException, IOException
            {
                HttpSession session = request.getSession(true);
                session.setAttribute(SessionConstants.ATTRIBUTE_NAME, SessionConstants.ATTRIBUTE_VALUE);
            }
        }), "/session");
        startServer();
        prepareClient();
        startClient();

        // Make an HTTP request to prime the HttpSession
        URI uri = URI.create("http://localhost:" + connector.getLocalPort() + "/session");
        ContentResponse response = httpClient.newRequest(uri)
                .path("/session")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(200, response.getStatus());

        List<HttpCookie> cookies = httpClient.getCookieStore().get(uri);
        Assert.assertNotNull(cookies);
        HttpCookie sessionCookie = null;
        for (HttpCookie cookie : cookies)
        {
            if ("jsessionid".equalsIgnoreCase(cookie.getName()))
                sessionCookie = cookie;
        }
        Assert.assertNotNull(sessionCookie);

        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            public void sessionAdded(ServerSession session)
            {
                Assert.assertNotNull(bayeux.getContext().getHttpSessionId());
                Assert.assertEquals(SessionConstants.ATTRIBUTE_VALUE, bayeux.getContext().getHttpSessionAttribute(SessionConstants.ATTRIBUTE_NAME));
                latch.countDown();
            }

            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.getCookieStore().add(uri, sessionCookie);
        client.handshake();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testContextAttribute() throws Exception
    {
        prepareAndStart(null);

        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            public void sessionAdded(ServerSession session)
            {
                Assert.assertSame(bayeux, bayeux.getContext().getContextAttribute(BayeuxServer.ATTRIBUTE));
                latch.countDown();
            }

            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client);
    }

    public interface CookieConstants
    {
        public static final String COOKIE_NAME = "name";
        public static final String COOKIE_VALUE = "value";
    }

    public static class CookieWebSocketTransport extends WebSocketTransport implements CookieConstants
    {
        public CookieWebSocketTransport(BayeuxServerImpl bayeux)
        {
            super(bayeux);
        }

        @Override
        protected void modifyHandshake(HandshakeRequest request, HandshakeResponse response)
        {
            response.getHeaders().put("Set-Cookie", Collections.singletonList(COOKIE_NAME + "=" + COOKIE_VALUE));
        }
    }

    public static class CookieJettyWebSocketTransport extends JettyWebSocketTransport implements CookieConstants
    {
        public CookieJettyWebSocketTransport(BayeuxServerImpl bayeux)
        {
            super(bayeux);
        }

        @Override
        protected void modifyUpgrade(ServletUpgradeRequest request, ServletUpgradeResponse response)
        {
            response.setHeader("Set-Cookie", COOKIE_NAME + "=" + COOKIE_VALUE);
        }
    }

    public interface SessionConstants
    {
        public static final String ATTRIBUTE_NAME = "name";
        public static final String ATTRIBUTE_VALUE = "value";
    }

    public static class SessionWebSocketTransport extends WebSocketTransport implements SessionConstants
    {
        public SessionWebSocketTransport(BayeuxServerImpl bayeux)
        {
            super(bayeux);
        }

        @Override
        protected void modifyHandshake(HandshakeRequest request, HandshakeResponse response)
        {
            HttpSession session = (HttpSession)request.getHttpSession();
            Assert.assertNotNull(session);
            Assert.assertEquals(ATTRIBUTE_VALUE, session.getAttribute(ATTRIBUTE_NAME));
        }
    }

    public static class SessionJettyWebSocketTransport extends JettyWebSocketTransport implements SessionConstants
    {
        public SessionJettyWebSocketTransport(BayeuxServerImpl bayeux)
        {
            super(bayeux);
        }

        @Override
        protected void modifyUpgrade(ServletUpgradeRequest request, ServletUpgradeResponse response)
        {
            HttpSession session = request.getSession();
            Assert.assertNotNull(session);
            Assert.assertEquals(ATTRIBUTE_VALUE, session.getAttribute(ATTRIBUTE_NAME));
        }
    }
}
