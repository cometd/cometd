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

import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.FilterMapping;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDWebSocketConnectTimeoutTest extends AbstractCometDWebSocketTest {
    private final long timeout = 1000;

    @Test
    public void testConnectTimeout() throws Exception {
        TimeoutFilter filter = new TimeoutFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.getServletHandler().prependFilter(filterHolder);
        FilterMapping mapping = new FilterMapping();
        mapping.setFilterName(filterHolder.getName());
        mapping.setPathSpec(cometdServletPath + "/*");
        mapping.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        context.getServletHandler().prependFilterMapping(mapping);

        evaluateScript("""
                // Need long-polling as a fallback after websocket fails.
                cometd.registerTransport('long-polling', originalTransports['long-polling']);
                const wsLatch = new Latch(1);
                const lpLatch = new Latch(1);
                cometd.addListener('/meta/handshake', message => {
                   if (cometd.getTransport().getType() === 'websocket' && !message.successful) {
                       wsLatch.countDown();
                   } else if (cometd.getTransport().getType() === 'long-polling' && message.successful) {
                       lpLatch.countDown();
                   }
                });
                const failureLatch = new Latch(1);
                cometd.onTransportException = (failure, oldTransport, newTransport) => {
                    failureLatch.countDown();
                };
                cometd.init({url: '$U', logLevel: '$L', connectTimeout: $T});
                """.replace("$U", cometdURL).replace("$L", getLogLevel())
                .replace("$T", String.valueOf(timeout)));

        Latch failureLatch = javaScript.get("failureLatch");
        Assertions.assertTrue(failureLatch.await(2 * timeout));
        Latch wsLatch = javaScript.get("wsLatch");
        Assertions.assertTrue(wsLatch.await(2 * timeout));
        Latch lpLatch = javaScript.get("lpLatch");
        Assertions.assertTrue(lpLatch.await(2 * timeout));

        disconnect();
    }

    @Test
    public void testConnectTimeoutIsCanceledOnSuccessfulConnect() throws Exception {
        evaluateScript("""
                const handshakeLatch = new Latch(1);
                cometd.addListener('/meta/handshake', message => {
                   if (cometd.getTransport().getType() === 'websocket' && message.successful) {
                       handshakeLatch.countDown();
                   }
                });
                const connectLatch = new Latch(1);
                cometd.addListener('/meta/connect', message => {
                   if (!message.successful) {
                       connectLatch.countDown();
                   }
                });
                cometd.init({url: '$U', logLevel: '$L', connectTimeout: $T});
                """.replace("$U", cometdURL).replace("$L", getLogLevel())
                .replace("$T", String.valueOf(timeout)));
        
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Assertions.assertTrue(handshakeLatch.await(2 * timeout));

        // Wait to be sure we're not disconnected.
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertFalse(connectLatch.await(2 * timeout));

        disconnect();
    }

    private class TimeoutFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            String upgrade = request.getHeader("Upgrade");
            if (upgrade != null) {
                sleep(3 * timeout);
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }
}
