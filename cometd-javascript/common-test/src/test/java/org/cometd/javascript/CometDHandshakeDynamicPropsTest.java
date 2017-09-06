/*
 * Copyright (c) 2008-2017 the original author or authors.
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

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Assert;
import org.junit.Test;

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
        StringBuilder script = new StringBuilder();
        script.append("cometd.configure({url: '").append(cometdURL);
        script.append("', logLevel: '").append(getLogLevel()).append("'});");
        script.append("var outHandshake = undefined;");
        script.append("var outLatch = new Latch(1);");
        script.append("cometd.registerExtension('test', {");
        script.append("    outgoing: function(message) {");
        script.append("        if ('/meta/handshake' == message.channel) {");
        script.append("            outHandshake = message;");
        script.append("            outLatch.countDown();");
        script.append("        }");
        script.append("    }");
        script.append("});");
        script.append("var inHandshake = undefined;");
        script.append("var inLatch = new Latch(1);");
        script.append("cometd.addListener('/meta/handshake', function(message) {");
        script.append("    inHandshake = message;");
        script.append("    inLatch.countDown();");
        script.append("});");
        evaluateScript(script.toString());
        script.setLength(0);
        Latch outLatch = javaScript.get("outLatch");
        script.append("var handshakeProps = { ext: { token: 1 } };");
        script.append("cometd.handshake(handshakeProps)");
        evaluateScript(script.toString());
        script.setLength(0);
        Assert.assertTrue(outLatch.await(5000));

        evaluateScript("window.assert(outHandshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(outHandshake.ext !== undefined, 'handshake without ext');");
        int token = ((Number)evaluateScript("outHandshake.ext.token")).intValue();
        Assert.assertEquals(1, token);

        Latch inLatch = javaScript.get("inLatch");
        Assert.assertTrue(inLatch.await(5000));

        String clientId = evaluateScript("inHandshake.clientId");
        evaluateScript("outHandshake = undefined;");
        evaluateScript("handshakeProps.ext.token = 2;");
        evaluateScript("outLatch = new Latch(1);");
        outLatch = javaScript.get("outLatch");

        // This triggers a re-handshake
        filter.setClientId(clientId);

        // Wait for the re-handshake
        Assert.assertTrue(outLatch.await(5000));

        evaluateScript("window.assert(outHandshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(outHandshake.ext !== undefined, 'handshake without ext');");
        token = ((Number)evaluateScript("outHandshake.ext.token")).intValue();
        Assert.assertEquals(2, token);

        disconnect();
    }

    public class BayeuxFilter implements Filter {
        private boolean handshook;
        private String clientId;

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            if (handshook) {
                synchronized (this) {
                    while (clientId == null) {
                        try {
                            wait();
                        } catch (InterruptedException x) {
                            throw new ServletException(x);
                        }
                    }
                    // Remove the client, so that the CometD implementation will send
                    // "unknown client" and the JavaScript will re-handshake
                    ServerSession session = bayeuxServer.getSession(clientId);
                    if (session != null) {
                        bayeuxServer.removeServerSession(session, false);
                    }
                }
            } else {
                handshook = true;
            }
            chain.doFilter(request, response);
        }

        public void setClientId(String clientId) {
            synchronized (this) {
                this.clientId = clientId;
                notifyAll();
            }
        }

        @Override
        public void destroy() {
        }
    }
}
