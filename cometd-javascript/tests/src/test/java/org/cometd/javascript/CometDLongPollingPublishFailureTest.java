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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDLongPollingPublishFailureTest extends AbstractCometDLongPollingTest {
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception {
        super.customizeContext(context);
        PublishThrowingFilter filter = new PublishThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Test
    public void testPublishFailures() throws Exception {
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', function() { subscribeLatch.countDown(); });");
        evaluateScript("var subscription = cometd.subscribe('/echo', function() { subscribeLatch.countDown(); });");
        Assertions.assertTrue(subscribeLatch.await(5000));

        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = javaScript.get("publishLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = javaScript.get("failureLatch");
        evaluateScript("cometd.addListener('/meta/publish', function() { publishLatch.countDown(); });");
        evaluateScript("cometd.addListener('/meta/unsuccessful', function() { failureLatch.countDown(); });");
        evaluateScript("cometd.publish('/echo', 'test');");
        Assertions.assertTrue(publishLatch.await(5000));
        Assertions.assertTrue(failureLatch.await(5000));

        // Be sure there is no backoff
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        int backoff = ((Number)javaScript.get("backoff")).intValue();
        Assertions.assertEquals(0, backoff);

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assertions.assertTrue(disconnectLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assertions.assertEquals("disconnected", status);
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
            // The second non-handshake and non-connect message will be the publish, throw
            if (messages == 2) {
                throw new IOException();
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }
}
