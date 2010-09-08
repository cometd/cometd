package org.cometd.server;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SpringFrameworkConfigurationTest
{
    @Test
    public void testFullSpringConfiguration() throws Exception
    {
        Server server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup comet servlet
        CometdServlet cometdServlet = new CometdServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
//        cometdServletHolder.setInitParameter("timeout", String.valueOf(5000));
//        cometdServletHolder.setInitParameter("logLevel", "3");
//        cometdServletHolder.setInitParameter("jsonDebug", "true");
        cometdServletHolder.setInitOrder(1);
        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        // Add Spring listener
        context.addEventListener(new ContextLoaderListener());
        context.getInitParams().put(ContextLoader.CONFIG_LOCATION_PARAM, "classpath:/applicationContext-full.xml");

        server.start();
        try
        {
            WebApplicationContext applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(context.getServletContext());
            assertNotNull(applicationContext);

            String logLevel = (String)applicationContext.getBean("logLevel");

            BayeuxServerImpl bayeuxServer = (BayeuxServerImpl)applicationContext.getBean("bayeux");
            assertNotNull(bayeuxServer);
            assertTrue(bayeuxServer.isStarted());
            assertEquals(logLevel, bayeuxServer.getOption("logLevel"));

            assertSame(bayeuxServer, cometdServlet.getBayeux());
            assertFalse(cometdServlet.getTransports().isEmpty());

            int port = connector.getLocalPort();
            String bayeuxURL = "http://localhost:" + port + contextPath + cometdServletPath;

            HttpClient httpClient = new HttpClient();
            httpClient.start();
            try
            {
                ContentExchange handshake = new ContentExchange(true);
                handshake.setURL(bayeuxURL);
                handshake.setMethod(HttpMethods.POST);
                handshake.setRequestContentType("application/json;charset=UTF-8");
                String handshakeBody = "" +
                        "[{" +
                        "\"channel\": \"/meta/handshake\"," +
                        "\"version\": \"1.0\"," +
                        "\"minimumVersion\": \"1.0\"," +
                        "\"supportedConnectionTypes\": [\"long-polling\"]" +
                        "}]";
                handshake.setRequestContent(new ByteArrayBuffer(handshakeBody, "UTF-8"));
                httpClient.send(handshake);
                assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
                assertEquals(200, handshake.getResponseStatus());
            }
            finally
            {
                httpClient.stop();
            }
        }
        finally
        {
            server.stop();
            server.join();
        }
    }
}
