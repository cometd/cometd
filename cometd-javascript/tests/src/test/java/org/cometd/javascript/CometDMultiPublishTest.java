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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDMultiPublishTest extends AbstractCometDLongPollingTest {
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception {
        super.customizeContext(context);
        PublishThrowingFilter filter = new PublishThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Test
    public void testMultiPublish() throws Throwable {
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', function() { subscribeLatch.countDown(); });");
        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("cometd.subscribe('/echo', function() { latch.countDown(); });");
        Assertions.assertTrue(subscribeLatch.await(5000));

        evaluateScript("var Handler = Java.type('" + Handler.class.getName() + "')");
        evaluateScript("var handler = new Handler();");
        Handler handler = javaScript.get("handler");
        evaluateScript("cometd.addListener('/meta/publish', function(m) { handler.handle(m); });");
        evaluateScript("var disconnect = new Latch(1);");
        Latch disconnect = javaScript.get("disconnect");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnect.countDown(); });");

        AtomicReference<List<Throwable>> failures = new AtomicReference<>(new ArrayList<>());
        handler.expect(failures, 4);
        disconnect.reset(1);

        // These publish are sent without waiting each one to return,
        // so they will be queued. The second publish will fail, we
        // expect the following to fail as well, in order.
        evaluateScript("cometd.publish('/echo', {id: 1});" +
                "cometd.publish('/echo', {id: 2});" +
                "cometd.publish('/echo', {id: 3});" +
                "cometd.publish('/echo', {id: 4});" +
                "cometd.disconnect();");

        Assertions.assertTrue(latch.await(5000));
        Assertions.assertTrue(handler.await(5000), failures.get().toString());
        Assertions.assertTrue(failures.get().isEmpty(), failures.get().toString());
        Assertions.assertTrue(disconnect.await(5000));
    }

    public static class Handler {
        private int id;
        private AtomicReference<List<Throwable>> failures;
        private CountDownLatch latch;

        public void handle(Object jsMessage) {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>)jsMessage;
            Boolean successful = (Boolean)message.get("successful");
            ++id;
            if (id == 1) {
                // First publish should succeed
                if (successful == null || !successful) {
                    failures.get().add(new AssertionError("Publish " + id + " expected successful"));
                }
            } else if (id == 2 || id == 3 || id == 4) {
                // Second publish should fail because of the server
                // Third and fourth are soft failed by the CometD implementation
                if (successful == null || successful) {
                    failures.get().add(new AssertionError("Publish " + id + " expected unsuccessful"));
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>)((Map<String, Object>)((Map<String, Object>)message.get("failure")).get("message")).get("data");
                    int dataId = ((Number)data.get("id")).intValue();
                    if (dataId != id) {
                        failures.get().add(new AssertionError("data id " + dataId + ", expecting " + id));
                    }
                }
            }
            latch.countDown();
        }

        public void expect(AtomicReference<List<Throwable>> failures, int count) {
            this.failures = failures;
            latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }

    public static class PublishThrowingFilter implements Filter {
        private int messages;

        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            String uri = request.getRequestURI();
            if (!uri.endsWith("/handshake") && !uri.endsWith("/connect")) {
                ++messages;
            }
            // The third non-handshake and non-connect message will be the second publish, throw
            if (messages == 3) {
                throw new IOException();
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }
}
