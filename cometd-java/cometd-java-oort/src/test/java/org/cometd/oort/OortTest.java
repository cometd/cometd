package org.cometd.oort;

import java.util.ArrayList;
import java.util.List;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;

public abstract class OortTest
{
    private final List<Server> servers = new ArrayList<Server>();
    private final List<Oort> oorts = new ArrayList<Oort>();

    protected Server startServer(int port) throws Exception
    {
        Server server = new Server();
        Connector connector = new SelectChannelConnector();
        connector.setPort(port);
        server.addConnector(connector);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometdServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
//        cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitOrder(1);

        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        String url = "http://localhost:" + connector.getLocalPort() + contextPath + cometdServletPath;
        server.setAttribute(OortServlet.OORT_URL_PARAM, url);
        BayeuxServer bayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        server.setAttribute(BayeuxServer.ATTRIBUTE, bayeux);

        servers.add(server);

        return server;
    }

    protected Oort startOort(Server server) throws Exception
    {
        String url = (String)server.getAttribute(OortServlet.OORT_URL_PARAM);
        Oort oort = new Oort((BayeuxServer)server.getAttribute(BayeuxServer.ATTRIBUTE), url);
        oort.start();
        oorts.add(oort);
        return oort;
    }

    @After
    public void stop() throws Exception
    {
        stopOorts();
        stopServers();
    }

    protected void stopOorts() throws Exception
    {
        for (int i = oorts.size() - 1; i >= 0; --i)
            stopOort(oorts.get(i));
    }

    protected void stopOort(Oort oort) throws Exception
    {
        oort.stop();
    }

    protected void stopServers() throws Exception
    {
        for (int i = servers.size() - 1; i >= 0; --i)
            stopServer(servers.get(i));
    }

    protected void stopServer(Server server) throws Exception
    {
        server.stop();
        server.join();
    }
}
