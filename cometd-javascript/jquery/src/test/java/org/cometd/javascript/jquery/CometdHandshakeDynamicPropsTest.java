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

import org.cometd.Bayeux;
import org.cometd.javascript.Latch;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdHandshakeDynamicPropsTest extends AbstractCometdJQueryTest
{
    private BayeuxFilter filter;

    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        filter = new BayeuxFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    public void testHandshakeDynamicProps() throws Exception
    {
        defineClass(Latch.class);
        StringBuilder script = new StringBuilder();
        script.append("$.cometd.configure({url: '").append(cometdURL).append("', logLevel: 'debug'});");
        script.append("var outHandshake = undefined;");
        script.append("var outLatch = new Latch(1);");
        script.append("$.cometd.registerExtension('test', {");
        script.append("    outgoing: function(message)");
        script.append("    {");
        script.append("        if ('/meta/handshake' == message.channel)");
        script.append("        {");
        script.append("            outHandshake = message;");
        script.append("            outLatch.countDown();");
        script.append("        }");
        script.append("    }");
        script.append("});");
        script.append("var inHandshake = undefined;");
        script.append("var inLatch = new Latch(1);");
        script.append("$.cometd.addListener('/meta/handshake', function(message)");
        script.append("{");
        script.append("    inHandshake = message;");
        script.append("    inLatch.countDown();");
        script.append("});");
        evaluateScript(script.toString());
        script.setLength(0);
        Latch outLatch = get("outLatch");
        script.append("var handshakeProps = { ext: { token: 1 } };");
        script.append("$.cometd.handshake(handshakeProps)");
        evaluateScript(script.toString());
        script.setLength(0);
        assertTrue(outLatch.await(1000));

        evaluateScript("window.assert(outHandshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(outHandshake.ext !== undefined, 'handshake without ext');");
        int token = ((Number)evaluateScript("outHandshake.ext.token")).intValue();
        assertEquals(1, token);

        Latch inLatch = get("inLatch");
        assertTrue(inLatch.await(1000));

        String clientId = evaluateScript("inHandshake.clientId");
        evaluateScript("outHandshake = undefined;");
        evaluateScript("handshakeProps.ext.token = 2;");
        evaluateScript("outLatch = new Latch(1);");
        outLatch = get("outLatch");

        // This triggers a re-handshake
        filter.setClientId(clientId);

        // Wait for the re-handshake
        assertTrue(outLatch.await(1000));

        evaluateScript("window.assert(outHandshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(outHandshake.ext !== undefined, 'handshake without ext');");
        token = ((Number)evaluateScript("outHandshake.ext.token")).intValue();
        assertEquals(2, token);

        evaluateScript("$.cometd.disconnect(true);");
    }

    public class BayeuxFilter implements Filter
    {
        private boolean handshook;
        private String clientId;

        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (handshook)
            {
                synchronized (this)
                {
                    while (clientId == null)
                    {
                        try
                        {
                            wait();
                        }
                        catch (InterruptedException x)
                        {
                            throw new ServletException(x);
                        }
                    }
                    // Remove the client, so that the CometD implementation will send
                    // "unknown client" and the JavaScript will re-handshake
                    Bayeux bayeux = (Bayeux)request.getSession().getServletContext().getAttribute(Bayeux.ATTRIBUTE);
                    bayeux.removeClient(clientId);
                }
            }
            else
            {
                handshook = true;
            }
            chain.doFilter(request, response);
        }

        public void setClientId(String clientId)
        {
            synchronized (this)
            {
                this.clientId = clientId;
                notifyAll();
            }
        }

        public void destroy()
        {
        }
    }
}
