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
package org.cometd.javascript;

import java.io.IOException;
import java.util.EnumSet;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDHandshakeDynamicPropsTest extends AbstractCometDLongPollingTest {
    private BayeuxFilter filter;

    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception {
        super.customizeContext(context);
        filter = new BayeuxFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Test
    public void testHandshakeDynamicProps() throws Exception {
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                let outHandshake;
                let outLatch = new Latch(1);
                cometd.registerExtension('test', {
                    outgoing: message => {
                        if ('/meta/handshake' === message.channel) {
                            outHandshake = message;
                            outLatch.countDown();
                        }
                    }
                });
                let inHandshake;
                const inLatch = new Latch(1);
                cometd.addListener('/meta/handshake', message => {
                    inHandshake = message;
                    inLatch.countDown();
                });
                const handshakeProps = { ext: { token: 1 } };
                cometd.handshake(handshakeProps);
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Latch outLatch = javaScript.get("outLatch");
        Assertions.assertTrue(outLatch.await(5000));

        evaluateScript("""
            window.assert(outHandshake !== undefined, 'handshake is undefined');
            window.assert(outHandshake.ext !== undefined, 'handshake without ext');
            """);
        int token = ((Number)evaluateScript("outHandshake.ext.token")).intValue();
        Assertions.assertEquals(1, token);

        Latch inLatch = javaScript.get("inLatch");
        Assertions.assertTrue(inLatch.await(5000));

        evaluateScript("""
                outHandshake = undefined;
                handshakeProps.ext.token = 2;
                outLatch = new Latch(1);
                """);
        outLatch = javaScript.get("outLatch");

        // This triggers a re-handshake
        filter.setClientId(evaluateScript("inHandshake.clientId"));

        // Wait for the re-handshake
        Assertions.assertTrue(outLatch.await(5000));

        evaluateScript("""
                window.assert(outHandshake !== undefined, 'handshake is undefined');
                window.assert(outHandshake.ext !== undefined, 'handshake without ext');
                """);
        token = ((Number)evaluateScript("outHandshake.ext.token")).intValue();
        Assertions.assertEquals(2, token);

        disconnect();
    }

    public class BayeuxFilter implements Filter {
        private final AutoLock.WithCondition lock = new AutoLock.WithCondition();
        private boolean handshook;
        private String clientId;

        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            if (handshook) {
                try (AutoLock.WithCondition l = lock.lock()) {
                    while (clientId == null) {
                        try {
                            l.await();
                        } catch (InterruptedException x) {
                            throw new ServletException(x);
                        }
                    }
                    // Remove the client, so that the CometD implementation will
                    // send "unknown_session" and the JavaScript will re-handshake.
                    ServerSession session = bayeuxServer.getSession(clientId);
                    if (session != null) {
                        ((BayeuxServerImpl)bayeuxServer).removeServerSession(session, false);
                    }
                }
            } else {
                handshook = true;
            }
            chain.doFilter(request, response);
        }

        public void setClientId(String clientId) {
            try (AutoLock.WithCondition l = lock.lock()) {
                this.clientId = clientId;
                l.signalAll();
            }
        }

        @Override
        public void destroy() {
        }
    }
}
