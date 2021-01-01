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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Assert;
import org.junit.Test;

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
        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', readyLatch, 'countDown');");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assert.assertTrue(readyLatch.await(5000));

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', subscribeLatch, subscribeLatch.countDown);");
        evaluateScript("var subscription = cometd.subscribe('/echo', subscribeLatch, subscribeLatch.countDown);");
        Assert.assertTrue(subscribeLatch.await(5000));

        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("cometd.addListener('/meta/publish', publishLatch, publishLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);");
        evaluateScript("cometd.publish('/echo', 'test');");
        Assert.assertTrue(publishLatch.await(5000));
        Assert.assertTrue(failureLatch.await(5000));

        // Be sure there is no backoff
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        int backoff = ((Number)get("backoff")).intValue();
        Assert.assertEquals(0, backoff);

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);
    }

    public static class PublishThrowingFilter implements Filter {
        private int messages;

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
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
