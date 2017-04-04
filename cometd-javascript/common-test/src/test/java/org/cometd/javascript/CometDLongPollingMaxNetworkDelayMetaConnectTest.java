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

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Assert;
import org.junit.Test;

public class CometDLongPollingMaxNetworkDelayMetaConnectTest extends AbstractCometDLongPollingTest {
    private final long maxNetworkDelay = 2000;

    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception {
        super.customizeContext(context);
        DelayingFilter filter = new DelayingFilter(metaConnectPeriod + 2 * maxNetworkDelay);
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Test
    public void testMaxNetworkDelay() throws Exception {
        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(6);");
        Latch latch = get("latch");
        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "maxNetworkDelay: " + maxNetworkDelay + ", " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");
        evaluateScript("var connects = 0;");
        evaluateScript("var failure;");
        evaluateScript("cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "    ++connects;" +
                "    if (connects === 1 && message.successful ||" +
                "        connects === 2 && !message.successful ||" +
                "        connects === 3 && message.successful ||" +
                "        connects === 4 && !message.successful ||" +
                "        connects === 5 && message.successful ||" +
                "        connects === 6 && message.successful)" +
                "        latch.countDown();" +
                "    else if (!failure)" +
                "        failure = 'Failure at connect #' + connects;" +
                "});");

        evaluateScript("cometd.handshake();");

        // First connect (id=2) returns immediately (time = 0)
        // Second connect (id=3) is delayed, but client is not aware of this
        // MaxNetworkDelay elapses, second connect is failed on the client (time = metaConnectPeriod + maxNetworkDelay)
        // Client sends third connect (id=4)
        // Third connect returns immediately
        // Fourth connect (id=5) is held
        // Second connect is processed on server (time = metaConnectPeriod + 2 * maxNetworkDelay)
        //  + Fourth connect is replied with a 408
        //  + Second connect is held
        // Client sends fifth connect (id=6)
        // Fifth connect returns immediately
        // Client sends sixth connect (id=7) and it is processed on server:
        //  + Second connect is replied with a 408, but connection is already closed by the client
        //  + Sixth connect is held
        // Sixth connect returns (time = 2 * metaConnectPeriod + 2 * maxNetworkDelay)

        Assert.assertTrue(latch.await(2 * metaConnectPeriod + 3 * maxNetworkDelay));
        evaluateScript("window.assert(failure === undefined, failure);");

        evaluateScript("cometd.disconnect(true);");
    }

    private class DelayingFilter implements Filter {
        private final AtomicInteger connects = new AtomicInteger();
        private final long delay;

        public DelayingFilter(long delay) {
            this.delay = delay;
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            String uri = request.getRequestURI();
            if (uri.endsWith("/connect")) {
                int connects = this.connects.incrementAndGet();
                // We hold the second connect longer than the long poll timeout + maxNetworkDelay
                if (connects == 2) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException x) {
                        throw new IOException();
                    }
                }
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }
}
