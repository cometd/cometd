package org.cometd.client;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * @version $Revision: 781 $ $Date: 2009-10-08 19:34:08 +1100 (Thu, 08 Oct 2009) $
 */
public class BayeuxLoadServer
{
    public static void main(String[] args) throws Exception
    {
        boolean qos=false;
        boolean stats=false;
        int port = 8080;
        
        for (String arg : args)
        {
            qos |= "--qos".equals(arg);
            stats |= "--stats".equals(arg);
            if (!arg.startsWith("--"))
                port = Integer.parseInt(arg);
        }
            

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
        
        HandlerWrapper handler = server;
        StatisticsHandler statisticsHandler = null;
        if (stats)
        {
            statisticsHandler = new StatisticsHandler();
            handler.setHandler(statisticsHandler);
            handler = statisticsHandler;
        }

        if (qos)
        {
            RequestQoSHandler requestQoSHandler = new RequestQoSHandler();
            handler.setHandler(requestQoSHandler); 
            handler = requestQoSHandler;
        }

        // Add more handlers if needed

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
            boolean started = helper.startStatistics();
            if (started && statisticsHandler!=null)
            {
                statisticsHandler.statsReset();
            }
        }

        public void stopStatistics(ServerSession remote, Message message) throws Exception
        {
            boolean stopped = helper.stopStatistics();
            if (stopped && statisticsHandler!=null)
            {
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

    private static class RequestQoSHandler extends HandlerWrapper
    {
        private final long maxRequestTime = 500;
        private final AtomicLong requestIds = new AtomicLong();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        @Override
        protected void doStop() throws Exception
        {
            super.doStop();
            scheduler.shutdown();
        }

        @Override
        public void handle(String target, Request request, final HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            final long requestId = requestIds.incrementAndGet();
            final AtomicBoolean longRequest = new AtomicBoolean(false);
            final Thread thread = Thread.currentThread();
            ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(new Runnable()
            {
                public void run()
                {
                    onLongRequestDetected(requestId, httpRequest, thread);
                    longRequest.set(true);
                }
            }, maxRequestTime, maxRequestTime, TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            try
            {
                super.handle(target, request, httpRequest, httpResponse);
            }
            finally
            {
                long end = System.nanoTime();
                task.cancel(false);
                if (longRequest.get())
                {
                    onLongRequestEnded(requestId, end - start);
                }
            }
        }

        private void onLongRequestDetected(long requestId, HttpServletRequest request, Thread thread)
        {
            StackTraceElement[] stackFrames = thread.getStackTrace();
            StringBuilder builder = new StringBuilder();
            builder.append(request.getRequestURI()).append("\n");
            for (Enumeration<String> headers = request.getHeaderNames(); headers.hasMoreElements();)
            {
                String name = headers.nextElement();
                builder.append(name).append("=").append(Collections.list(request.getHeaders(name))).append("\n");
            }
            builder.append(request.getRemoteAddr()).append(":").append(request.getRemotePort()).append(" => ");
            builder.append(request.getLocalAddr()).append(":").append(request.getLocalPort()).append("\n");
            builder.append(thread).append("\n");
            formatStackFrames(stackFrames, builder);
            System.err.println("Request #" + requestId + " is too slow (> " + maxRequestTime + " ms)\n" + builder);
        }

        private void onLongRequestEnded(long requestId, long time)
        {
            System.err.println("Request #" + requestId + " lasted " + TimeUnit.NANOSECONDS.toMillis(time) + " ms");
        }

        private void formatStackFrames(StackTraceElement[] stackFrames, StringBuilder builder)
        {
            for (int i = 0; i < stackFrames.length; ++i)
            {
                StackTraceElement stackFrame = stackFrames[i];
                for (int j = 0; j < i; ++j)
                    builder.append(" ");
                builder.append(stackFrame).append("\n");
            }
        }
    }
}
