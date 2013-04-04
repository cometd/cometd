/*
 * Copyright (c) 2011 the original author or authors.
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
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometdServlet;
import org.cometd.websocket.server.WebSocketTransport;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Assert;
import org.junit.Test;

public class JMXTest extends OortTest
{
    @Test
    public void testJMX() throws Exception
    {
        Server server = new Server();
        Connector connector = new SelectChannelConnector();
        server.addConnector(connector);

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
        server.getContainer().addEventListener(mbeanContainer);
        server.addBean(mbeanContainer);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        String value = BayeuxServerImpl.ATTRIBUTE + "," + Oort.OORT_ATTRIBUTE + "," + Seti.SETI_ATTRIBUTE;
        context.setInitParameter(ServletContextHandler.MANAGED_ATTRIBUTES, value);

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometdServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("transports", WebSocketTransport.class.getName());
        if (Boolean.getBoolean("debugTests"))
            cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitOrder(1);
        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        ServletHolder oortServletHolder = new ServletHolder(OortStaticConfigServlet.class);
        oortServletHolder.setInitParameter(OortConfigServlet.OORT_URL_PARAM, "http://localhost" + contextPath + cometdServletPath);
        oortServletHolder.setInitParameter(OortConfigServlet.OORT_CLIENT_DEBUG_PARAM, System.getProperty("debugTests"));
        oortServletHolder.setInitOrder(2);
        context.addServlet(oortServletHolder, "/oort");

        ServletHolder setiServletHolder = new ServletHolder(SetiServlet.class);
        setiServletHolder.setInitOrder(3);
        context.addServlet(setiServletHolder, "/seti");

        server.start();

        String domain = BayeuxServerImpl.class.getPackage().getName();
        Set<ObjectName> mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*"), null);
        Assert.assertEquals(1, mbeanNames.size());
        ObjectName objectName = mbeanNames.iterator().next();
        Set <String> channels = (Set<String>)mbeanServer.getAttribute(objectName, "channels");
        Assert.assertTrue(channels.size() > 0);

        domain = Oort.class.getPackage().getName();
        mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*,type=oort"), null);
        Assert.assertEquals(1, mbeanNames.size());
        objectName = mbeanNames.iterator().next();

        String channel = "/foo";
        mbeanServer.invoke(objectName, "observeChannel", new Object[]{channel}, new String[]{String.class.getName()});
        channels = (Set<String>)mbeanServer.getAttribute(objectName, "observedChannels");
        Assert.assertTrue(channels.contains(channel));

        domain = Seti.class.getPackage().getName();
        mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*,type=seti"), null);
        Assert.assertEquals(1, mbeanNames.size());
        objectName = mbeanNames.iterator().next();
        ObjectName oortObjectName = (ObjectName)mbeanServer.getAttribute(objectName, "oort");
        Assert.assertEquals("oort", oortObjectName.getKeyProperty("type"));

        server.stop();
    }
}
