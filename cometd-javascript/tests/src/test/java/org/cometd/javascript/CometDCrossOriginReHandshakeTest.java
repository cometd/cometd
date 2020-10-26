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
package org.cometd.javascript;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDCrossOriginReHandshakeTest extends AbstractCometDLongPollingTest {
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception {
        super.customizeContext(context);
        context.addFilter(new FilterHolder(new CrossOriginFilter()), cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(new FilterHolder(new ConnectThrowingFilter()), cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Test
    public void testCrossOriginReHandshakeDoesNotChangeTransportType() throws Exception {
        bayeuxServer.addExtension(new ReHandshakeExtension());

        String crossOriginCometDURL = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({" +
                "url: '" + crossOriginCometDURL + "', " +
                "requestHeaders: { Origin: 'http://localhost:8080' }, " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");
        evaluateScript("var handshakeLatch = new Latch(2);");
        evaluateScript("var connectLatch = new Latch(2);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("cometd.addListener('/meta/handshake', function() { handshakeLatch.countDown(); });");
        evaluateScript("cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });");
        evaluateScript("cometd.handshake();");

        Assertions.assertTrue(connectLatch.await(metaConnectPeriod + 5000));
        Assertions.assertTrue(handshakeLatch.await(5000));
        Assertions.assertEquals("long-polling", evaluateScript("cometd.getTransport().getType()"));

        disconnect();
    }

    private class ReHandshakeExtension implements BayeuxServer.Extension {
        private final AtomicInteger connects = new AtomicInteger();

        @Override
        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message) {
            return false;
        }

        @Override
        public boolean sendMeta(ServerSession session, ServerMessage.Mutable message) {
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                int connects = this.connects.incrementAndGet();
                if (connects == 1) {
                    // Fake the removal of the session due to timeout
                    bayeuxServer.removeServerSession(session, true);
                }
            }
            return true;
        }
    }

    private static class ConnectThrowingFilter implements Filter {
        private final AtomicInteger connects = new AtomicInteger();

        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            String uri = request.getRequestURI();
            if (uri.endsWith("/connect")) {
                int connects = this.connects.incrementAndGet();
                if (connects == 2) {
                    throw new IOException();
                }
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }
}
