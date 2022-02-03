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
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.servlet.FilterHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDLongPollingSubscribeFailureTest extends AbstractCometDLongPollingTest {
    @Test
    public void testSubscribeFailure() throws Exception {
        SubscribeThrowingFilter filter = new SubscribeThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = javaScript.get("failureLatch");
        String script = "cometd.addListener('/meta/subscribe', function() { subscribeLatch.countDown(); });";
        script += "cometd.addListener('/meta/unsuccessful', function() { failureLatch.countDown(); });";
        evaluateScript(script);

        evaluateScript("cometd.subscribe('/echo', function() { subscribeLatch.countDown(); });");
        Assertions.assertTrue(subscribeLatch.await(5000));
        Assertions.assertTrue(failureLatch.await(5000));

        // Be sure there is no backoff
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        int backoff = ((Number)javaScript.get("backoff")).intValue();
        Assertions.assertEquals(0, backoff);

        disconnect();
    }

    @Test
    public void testSubscribeFailedOnlyOnClient() throws Exception {
        long maxNetworkDelay = 2000;
        long sleep = maxNetworkDelay + maxNetworkDelay / 2;

        bayeuxServer.getChannel(Channel.META_SUBSCRIBE).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException x) {
                    // Ignored.
                }
                return true;
            }
        });

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'," +
                "maxNetworkDelay: " + maxNetworkDelay + "})");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("var messageLatch = new Latch(1);");
        Latch messageLatch = javaScript.get("messageLatch");
        String channelName = "/echo";
        evaluateScript("cometd.subscribe('" + channelName + "', function() { messageLatch.countDown(); }, " +
                "function(reply) {" +
                "   if (reply.successful === false) {" +
                "       subscribeLatch.countDown();" +
                "   }" +
                "});");
        Assertions.assertTrue(subscribeLatch.await(5000));

        // Wait for the subscription to happen on server.
        Thread.sleep(sleep);

        // Subscription has failed on the client, but not on server.
        // Emitting a message on server must not be received by the client.
        bayeuxServer.getChannel(channelName).publish(null, "data", Promise.noop());
        Assertions.assertFalse(messageLatch.await(1000));

        disconnect();
    }

    public static class SubscribeThrowingFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            String uri = request.getRequestURI();
            if (!uri.endsWith("/handshake") &&
                    !uri.endsWith("/connect") &&
                    !uri.endsWith("/disconnect")) {
                throw new IOException();
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }
}
