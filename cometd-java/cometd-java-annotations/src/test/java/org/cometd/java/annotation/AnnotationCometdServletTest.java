package org.cometd.java.annotation;

import java.util.List;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Assert;
import org.junit.Test;

public class AnnotationCometdServletTest
{
    @Test
    public void testLifecycle() throws Exception
    {
        Server server = new Server();

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        AnnotationCometdServlet cometdServlet = new AnnotationCometdServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        cometdServletHolder.setInitParameter("services", TestService.class.getName());
        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();

        List<Object> services = cometdServlet.getServices();
        Assert.assertNotNull(services);
        Assert.assertEquals(1, services.size());
        TestService service = (TestService)services.get(0);
        Assert.assertTrue(service.init);

        server.stop();
        server.join();

        Assert.assertTrue(service.destroy);
    }

    @Service("test")
    public static class TestService
    {
        public boolean init;
        public boolean destroy;

        @PostConstruct
        public void init()
        {
            init = true;
        }

        @PreDestroy
        public void destroy()
        {
            destroy = true;
        }
    }
}
