package org.cometd.client;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * @version $Revision: 781 $ $Date: 2009-10-08 19:34:08 +1100 (Thu, 08 Oct 2009) $
 */
public class BayeuxDemoServer
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

        StatisticsHandler statisticsHandler = new StatisticsHandler();
        server.setHandler(statisticsHandler);

        // Add more handlers if needed

        HandlerContainer handler = statisticsHandler;

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(handler, contextPath, ServletContextHandler.SESSIONS);

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup comet servlet
        String cometServletPath = "/cometd";
        CometdServlet cometServlet = new CometdServlet();
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

        BayeuxServer bayeux = cometServlet.getBayeux();
        new StatisticsService(bayeux, statisticsHandler);
    }

    public static class StatisticsService extends AbstractService
    {
        private final StatisticsHelper helper = new StatisticsHelper();
        private final StatisticsHandler statisticsHandler;

        private StatisticsService(BayeuxServer bayeux, StatisticsHandler statisticsHandler)
        {
            super(bayeux, "statistics-service");
            this.statisticsHandler = statisticsHandler;
            addService("/service/statistics/start", "startStatistics");
            addService("/service/statistics/stop", "stopStatistics");
        }

        public void startStatistics(ServerSession remote, Message message)
        {
            helper.startStatistics();
            statisticsHandler.statsReset();
        }

        public void stopStatistics(ServerSession remote, Message message) throws Exception
        {
            helper.stopStatistics();
            System.err.println("Requests (total/failed/max): " + statisticsHandler.getDispatched() + "/" +
                    (statisticsHandler.getResponses4xx() + statisticsHandler.getResponses5xx()) + "/" +
                    statisticsHandler.getDispatchedActiveMax());
            System.err.println("Requests times (total/avg/max - stddev): " +
                    statisticsHandler.getDispatchedTimeTotal() + "/" +
                    ((Double)statisticsHandler.getDispatchedTimeMean()).longValue() + "/" +
                    statisticsHandler.getDispatchedTimeMax() + " ms - " +
                    ((Double)statisticsHandler.getDispatchedTimeStdDev()).longValue());
            System.err.println();
        }
    }
}
