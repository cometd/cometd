package org.cometd.client;

import org.cometd.server.continuation.ContinuationCometdServlet;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxServer
{
    public static void main(String[] args) throws Exception
    {
        int port = 8080;
        if (args.length > 0)
            port = Integer.parseInt(args[0]);

        Server server = new Server();
        Connector connector = new SelectChannelConnector();
        connector.setPort(port);
        server.addConnector(connector);

        QueuedThreadPool threadPool = new QueuedThreadPool();
        server.setThreadPool(threadPool);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup comet servlet
        String cometServletPath = "/cometd";
        ContinuationCometdServlet cometServlet = new ContinuationCometdServlet();
        ServletHolder cometServletHolder = new ServletHolder(cometServlet);
        cometServletHolder.setInitParameter("maxInterval", String.valueOf(60000));
        context.addServlet(cometServletHolder, cometServletPath + "/*");

        server.start();
    }
}
