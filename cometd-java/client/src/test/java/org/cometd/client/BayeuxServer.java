package org.cometd.client;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.server.BayeuxService;
import org.cometd.server.continuation.ContinuationCometdServlet;
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
        SelectChannelConnector connector = new SelectChannelConnector();
        // Make sure the OS is configured properly for load testing;
        // see http://docs.codehaus.org/display/JETTY/HighLoadServers
        connector.setAcceptQueueSize(2048);
        // Make sure the server timeout on a TCP connection is large
        connector.setMaxIdleTime(240000);
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
        // Make sure the expiration timeout is large to avoid clients to timeout
        // This value must be several times larger than the client value
        // (e.g. 60 s on server vs 5 s on client) so that it's guaranteed that
        // it will be the client to dispose idle connections.
        cometServletHolder.setInitParameter("maxInterval", String.valueOf(60000));
        // Explicitly set the timeout value
        cometServletHolder.setInitParameter("timeout", String.valueOf(30000));
        context.addServlet(cometServletHolder, cometServletPath + "/*");

        server.start();

        Bayeux bayeux = cometServlet.getBayeux();
        new StatisticsService(bayeux);
    }

    public static class StatisticsService extends BayeuxService
    {
        private final StatisticsHelper helper = new StatisticsHelper();

        private StatisticsService(Bayeux bayeux)
        {
            super(bayeux, "statistics-service");
            subscribe("/service/statistics/start", "startStatistics");
            subscribe("/service/statistics/stop", "stopStatistics");
        }

        public void startStatistics(Client remote, Message message)
        {
            helper.startStatistics();
        }

        public void stopStatistics(Client remote, Message message) throws Exception
        {
            helper.stopStatistics();
        }
    }
}
