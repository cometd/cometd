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
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.servlet.FilterHolder;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("The test filter is not called because the WSUpgradeFilter is added first")
public class CometDWebSocketHeaderRemovedTest extends AbstractCometDWebSocketTest {
    @Test
    public void testWebSocketHeaderRemoved() throws Exception {
        context.addFilter(new FilterHolder(new Filter() {
            @Override
            public void init(FilterConfig filterConfig) throws ServletException {
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                try {
                    // Wrap the response to remove the header
                    chain.doFilter(request, new HttpServletResponseWrapper((HttpServletResponse)response) {
                        @Override
                        public void addHeader(String name, String value) {
                            if (!"Sec-WebSocket-Accept".equals(name)) {
                                super.addHeader(name, value);
                            }
                        }
                    });
                } finally {
                    ((HttpServletResponse)response).setHeader("Sec-WebSocket-Accept", null);
                }
            }

            @Override
            public void destroy() {
            }
        }), cometdServletPath, EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        // Need long-polling as a fallback after websocket fails
        evaluateScript("cometd.registerTransport('long-polling', originalTransports['long-polling']);");

        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("cometd.addListener('/meta/handshake', function(message) {" +
                "   if (cometd.getTransport().getType() === 'long-polling' && message.successful) {" +
                "       latch.countDown();" +
                "   }" +
                "});");

        evaluateScript("cometd.handshake()");
        Assert.assertTrue(latch.await(5000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
    }
}
