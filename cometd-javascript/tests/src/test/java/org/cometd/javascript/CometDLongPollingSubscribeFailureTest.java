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

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDLongPollingSubscribeFailureTest extends AbstractCometDLongPollingTest {
    @Test
    public void testSubscribeFailure() throws Exception {
        SubscribeThrowingFilter filter = new SubscribeThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));

        evaluateScript("""
                const readyLatch = new Latch(1);
                cometd.addListener('/meta/connect', () => readyLatch.countDown());
                cometd.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Latch readyLatch = javaScript.get("readyLatch");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("""
                const subscribeLatch = new Latch(1);
                cometd.addListener('/meta/subscribe', () => subscribeLatch.countDown());
                const failureLatch = new Latch(1);
                cometd.addListener('/meta/unsuccessful', () => failureLatch.countDown());
                cometd.subscribe('/echo', () => subscribeLatch.countDown());
                """);
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assertions.assertTrue(subscribeLatch.await(5000));
        Latch failureLatch = javaScript.get("failureLatch");
        Assertions.assertTrue(failureLatch.await(5000));

        // Be sure there is no backoff
        int backoff = ((Number)evaluateScript("cometd.getBackoffPeriod()")).intValue();
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
                } catch (InterruptedException ignored) {
                }
                return true;
            }
        });

        evaluateScript("""
                const readyLatch = new Latch(1);
                cometd.addListener('/meta/connect', () => readyLatch.countDown());
                cometd.init({url: '$U', logLevel: '$L', maxNetworkDelay: $M});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$M", String.valueOf(maxNetworkDelay)));
        Latch readyLatch = javaScript.get("readyLatch");
        Assertions.assertTrue(readyLatch.await(5000));

        String channelName = "/echo";
        evaluateScript("""
                const subscribeLatch = new Latch(1);
                const messageLatch = new Latch(1);
                cometd.subscribe('$C', () => messageLatch.countDown(), reply => {
                   if (reply.successful === false) {
                       subscribeLatch.countDown();
                   }
                });
                """.replace("$C", channelName));
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assertions.assertTrue(subscribeLatch.await(5000));

        // Wait for the subscription to happen on server.
        Thread.sleep(sleep);

        // Subscription has failed on the client, but not on server.
        // Emitting a message on server must not be received by the client.
        bayeuxServer.getChannel(channelName).publish(null, "data", Promise.noop());
        Latch messageLatch = javaScript.get("messageLatch");
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
