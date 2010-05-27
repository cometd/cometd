// ========================================================================
// Copyright 2004-2008 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cometd.javascript.jquery;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.javascript.Latch;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;

/**
 * Tests that handshake failures will backoff correctly
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdHandshakeFailureTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        ThrowingFilter filter = new ThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    public void testHandshakeFailure() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("$.cometd.addListener('/meta/handshake', handshakeLatch, handshakeLatch.countDown);");
        evaluateScript("$.cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);");

        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        evaluateScript("var backoffIncrement = $.cometd.getBackoffIncrement();");
        int backoff = ((Number)get("backoff")).intValue();
        final int backoffIncrement = ((Number)get("backoffIncrement")).intValue();
        assertEquals(0, backoff);
        assertTrue(backoffIncrement > 0);

        evaluateScript("$.cometd.handshake();");
        assertTrue(handshakeLatch.await(1000));
        assertTrue(failureLatch.await(1000));

        // There is a failure, the backoff will be increased from 0 to backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        backoff = ((Number)get("backoff")).intValue();
        assertEquals(backoffIncrement, backoff);

        handshakeLatch.reset(1);
        failureLatch.reset(1);
        assertTrue(handshakeLatch.await(backoffIncrement));
        assertTrue(failureLatch.await(backoffIncrement));

        // Another failure, backoff will be increased to 2 * backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        backoff = ((Number)get("backoff")).intValue();
        assertEquals(2 * backoffIncrement, backoff);

        handshakeLatch.reset(1);
        failureLatch.reset(1);
        assertTrue(handshakeLatch.await(2 * backoffIncrement));
        assertTrue(failureLatch.await(2 * backoffIncrement));

        // Disconnect so that handshake is not performed anymore
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        failureLatch.reset(1);
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("$.cometd.disconnect();");
        assertTrue(disconnectLatch.await(1000));
        assertTrue(failureLatch.await(1000));
        String status = evaluateScript("$.cometd.getStatus();");
        assertEquals("disconnected", status);

        // Be sure the handshake is not retried anymore
        handshakeLatch.reset(1);
        assertFalse(handshakeLatch.await(4 * backoffIncrement));
    }

    public static class ThrowingFilter implements Filter
    {
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            String uri = request.getRequestURI();
            if (uri.endsWith("handshake"))
                throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
