/*
 * Copyright (c) 2008-2019 the original author or authors.
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

/**
 * Tests that failing the disconnect, the comet communication is aborted anyway
 */
public class CometDDisconnectFailureTest extends AbstractCometDTest {
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception {
        super.customizeContext(context);
        DisconnectThrowingFilter filter = new DisconnectThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Test
    public void testDisconnectFailure() throws Exception {
        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assert.assertTrue(readyLatch.await(5000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");

        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));

        // The test ends here, as we cannot get any information about the fact that
        // the long poll call returned (which we would have liked to, confirming that
        // a client-side only disconnect() actually stops the comet communication,
        // even if it fails).
        // The XmlHttpRequest specification says that if the response has not begun,
        // then aborting the XmlHttpRequest calling xhr.abort() does not result in
        // any notification. The network activity is stopped, but no notification is
        // emitted by calling onreadystatechange(). Therefore, comet cannot call any
        // callback to signal this, and any connect listener will not be notified.
    }

    public static class DisconnectThrowingFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            String uri = request.getRequestURI();
            if (uri.endsWith("/disconnect")) {
                throw new IOException();
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }
}
