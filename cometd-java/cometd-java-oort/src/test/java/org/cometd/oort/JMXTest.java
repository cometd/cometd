/*
 * Copyright (c) 2008-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.oort;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import jakarta.servlet.http.HttpServlet;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JMXTest {
    @Test
    public void testJMX() throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
        server.addBean(mbeanContainer);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        JakartaWebSocketServletContainerInitializer.configure(context, null);

        String value = BayeuxServerImpl.ATTRIBUTE + "," + Oort.OORT_ATTRIBUTE + "," + Seti.SETI_ATTRIBUTE;
        context.setInitParameter(ServletContextHandler.MANAGED_ATTRIBUTES, value);

        // CometD servlet
        String cometdServletPath = "/cometd";
        String cometdURLMapping = cometdServletPath + "/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        ServletHolder oortServletHolder = new ServletHolder(OortStaticConfigServlet.class);
        oortServletHolder.setInitParameter(OortConfigServlet.OORT_URL_PARAM, "http://localhost" + cometdServletPath);
        oortServletHolder.setInitOrder(2);
        context.addServlet(oortServletHolder, "/oort");

        ServletHolder setiServletHolder = new ServletHolder(SetiServlet.class);
        setiServletHolder.setInitOrder(3);
        context.addServlet(setiServletHolder, "/seti");

        server.start();

        String domain = BayeuxServerImpl.class.getPackage().getName();
        Set<ObjectName> mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*,type=bayeuxserverimpl"), null);
        Assertions.assertEquals(1, mbeanNames.size());
        ObjectName objectName = mbeanNames.iterator().next();
        int channels = (Integer)mbeanServer.getAttribute(objectName, "channelCount");
        Assertions.assertTrue(channels > 0);

        domain = Oort.class.getPackage().getName();
        mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*,type=oort"), null);
        Assertions.assertEquals(1, mbeanNames.size());
        objectName = mbeanNames.iterator().next();

        String channel = "/foo";
        mbeanServer.invoke(objectName, "observeChannel", new Object[]{channel}, new String[]{String.class.getName()});
        @SuppressWarnings("unchecked")
        Set<String> observedChannels = (Set<String>)mbeanServer.getAttribute(objectName, "observedChannels");
        Assertions.assertTrue(observedChannels.contains(channel));

        domain = Seti.class.getPackage().getName();
        mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*,type=seti"), null);
        Assertions.assertEquals(1, mbeanNames.size());
        objectName = mbeanNames.iterator().next();
        ObjectName oortObjectName = (ObjectName)mbeanServer.getAttribute(objectName, "oort");
        Assertions.assertEquals("oort", oortObjectName.getKeyProperty("type"));

        server.stop();

        domain = BayeuxServerImpl.class.getPackage().getName();
        mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*"), null);
        Assertions.assertEquals(0, mbeanNames.size());
    }

    @Test
    public void testPortableJMX() throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        JakartaWebSocketServletContainerInitializer.configure(context, null);

        // Do not use ServletContextHandler.MANAGED_ATTRIBUTES, so
        // the test can simulate a deployment on a non-Jetty Container.

        // CometD servlet
        String cometdServletPath = "/cometd";
        String cometdURLMapping = cometdServletPath + "/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        ServletHolder jmxServletHolder = new ServletHolder(CometDJMXExporter.class);
        jmxServletHolder.setInitOrder(2);
        context.addServlet(jmxServletHolder, "/jmx");

        server.start();

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        String domain = BayeuxServerImpl.class.getPackage().getName();
        Set<ObjectName> mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*,type=bayeuxserverimpl"), null);
        Assertions.assertEquals(1, mbeanNames.size());
        ObjectName objectName = mbeanNames.iterator().next();
        int channels = (Integer)mbeanServer.getAttribute(objectName, "channelCount");
        Assertions.assertTrue(channels > 0);

        server.stop();

        mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*"), null);
        Assertions.assertEquals(0, mbeanNames.size());
    }

    public static class CometDJMXExporter extends HttpServlet {
        private final List<Object> mbeans = new ArrayList<>();
        private volatile MBeanContainer mbeanContainer;

        @Override
        public void init() {
            mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
            BayeuxServer bayeuxServer = (BayeuxServer)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
            mbeanContainer.beanAdded(null, bayeuxServer);
            mbeans.add(bayeuxServer);
            // Add other components
        }

        @Override
        public void destroy() {
            for (int i = mbeans.size() - 1; i >= 0; --i) {
                mbeanContainer.beanRemoved(null, mbeans.get(i));
            }
        }
    }
}
