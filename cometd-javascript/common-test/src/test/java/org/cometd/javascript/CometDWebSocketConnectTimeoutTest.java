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

import org.eclipse.jetty.servlet.FilterHolder;
import org.junit.Assert;
import org.junit.Test;

public class CometDWebSocketConnectTimeoutTest extends AbstractCometDWebSocketTest {
    private final long timeout = 1000;

    @Test
    public void testConnectTimeout() throws Exception {
        context.stop();
        TimeoutFilter filter = new TimeoutFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
        context.start();

        defineClass(Latch.class);

        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("var wsLatch = new Latch(1);");
        Latch wsLatch = get("wsLatch");
        evaluateScript("var lpLatch = new Latch(1);");
        Latch lpLatch = get("lpLatch");

        // Need long-polling as a fallback after websocket fails
        evaluateScript("cometd.registerTransport('long-polling', originalTransports['long-polling']);");

        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "connectTimeout: " + timeout + ", " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");
        evaluateScript("cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "   if (cometd.getTransport().getType() === 'websocket' && !message.successful)" +
                "   {" +
                "       wsLatch.countDown();" +
                "   }" +
                "   else if (cometd.getTransport().getType() === 'long-polling' && message.successful)" +
                "   {" +
                "       lpLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.onTransportException = function(failure, oldTransport, newTransport)" +
                "{" +
                "    failureLatch.countDown();" +
                "};");

        evaluateScript("cometd.handshake()");
        Assert.assertTrue(failureLatch.await(2 * timeout));
        Assert.assertTrue(wsLatch.await(2 * timeout));
        Assert.assertTrue(lpLatch.await(2 * timeout));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
    }

    @Test
    public void testConnectTimeoutIsCanceledOnSuccessfulConnect() throws Exception {
        defineClass(Latch.class);

        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");

        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "connectTimeout: " + timeout + ", " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");
        evaluateScript("cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "   if (cometd.getTransport().getType() === 'websocket' && message.successful)" +
                "   {" +
                "       handshakeLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   if (!message.successful)" +
                "   {" +
                "       connectLatch.countDown();" +
                "   }" +
                "});");

        evaluateScript("cometd.handshake()");
        Assert.assertTrue(handshakeLatch.await(2 * timeout));

        // Wait to be sure we're not disconnected
        Assert.assertFalse(connectLatch.await(2 * timeout));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
    }

    private class TimeoutFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
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
