package org.cometd.client;

import java.util.Map;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;

public class ClientServerTest
{
    private Server server;
    private HttpClient httpClient;
    protected String cometdURL;
    protected BayeuxServer bayeux;
    protected BayeuxClient client;

    public void startServer(Map<String, String> initParams) throws Exception
    {
        server = new Server();

        Connector connector = new SelectChannelConnector();
        connector.setMaxIdleTime(30000);
        server.addConnector(connector);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometdServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitOrder(1);
        if (initParams != null)
        {
            for (Map.Entry<String, String> entry : initParams.entrySet())
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        }

        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + contextPath + cometdServletPath;

        bayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        httpClient = new HttpClient();
        httpClient.start();

        client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient));
    }

    @After
    public void stopServer() throws Exception
    {
        client.disconnect();
        client.waitFor(1000, BayeuxClient.State.DISCONNECTED);

        httpClient.stop();

        server.stop();
        server.join();
    }
}
