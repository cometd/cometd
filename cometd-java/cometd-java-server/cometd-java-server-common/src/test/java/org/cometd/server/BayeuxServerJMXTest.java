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
package org.cometd.server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import jakarta.servlet.http.HttpServlet;

import org.cometd.bayeux.server.BayeuxServer;
import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

public class BayeuxServerJMXTest {
    private Server server;
    private JMXServiceURL jmxURL;
    private BayeuxServerImpl bayeux;

    @BeforeEach
    public void setUp() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        int freePort = freePort();
        jmxURL = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:" + freePort + "/jmxrmi");
        String remoteRootObjectName = "org.eclipse.jetty:name=rmiconnectorserver";
        ConnectorServer connectorServer = new ConnectorServer(jmxURL, remoteRootObjectName);
        server.addBean(connectorServer);

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
        server.addBean(mbeanContainer);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        // CometD servlet
        String cometdServletPath = "/cometd";
        String cometdURLMapping = cometdServletPath + "/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        ServletHolder jmxServletHolder = new ServletHolder(CometDJMXExporter.class);
        jmxServletHolder.setInitOrder(2);
        context.addServlet(jmxServletHolder, "/jmx");

        server.start();
        bayeux = (BayeuxServerImpl)CometDJMXExporter.BAYEUX.get();
    }

    @AfterEach
    public void tearDown() {
        LifeCycle.stop(server);
    }

    @Test
    public void testLastSweepInfoOverRemoteJMX() throws Exception {
        try (JMXConnector client = JMXConnectorFactory.connect(jmxURL, null)) {
            for (int i = 0; i < 100; i++) {
                newServerSession(bayeux).scheduleExpiration(0, 0, 0);
            }

            MBeanServerConnection msc = client.getMBeanServerConnection();
            Set<ObjectName> mbeanNames = msc.queryNames(ObjectName.getInstance(BayeuxServerImpl.class.getPackage().getName() + ":*,type=bayeuxserverimpl"), null);
            ObjectName bayeuxObjectName = mbeanNames.iterator().next();
            AtomicReference<CompositeData> lastSweepInfoRef = new AtomicReference<>();
            await().pollDelay(1, TimeUnit.MILLISECONDS).atMost(5, TimeUnit.SECONDS).until(() -> {
                CompositeData lastSweepInfo = (CompositeData)msc.getAttribute(bayeuxObjectName, "lastSweepInfo");
                long lastSweepTransportDuration = (long)lastSweepInfo.get("serverSessionSweepCount");
                if (lastSweepTransportDuration != 0) {
                    lastSweepInfoRef.set(lastSweepInfo);
                    return true;
                }
                return false;
            });
            CompositeData lastSweepInfo = lastSweepInfoRef.get();
            assertThat(lastSweepInfo.containsKey("startInstant"), is(true));
            assertThat(lastSweepInfo.containsKey("transportSweepDuration"), is(true));
            assertThat(lastSweepInfo.containsKey("serverChannelSweepCount"), is(true));
            assertThat(lastSweepInfo.containsKey("serverChannelSweepDuration"), is(true));
            assertThat(((Number)lastSweepInfo.get("serverSessionSweepCount")).longValue(), greaterThan(0L));
            assertThat(lastSweepInfo.containsKey("serverSessionSweepDuration"), is(true));
            assertThat(lastSweepInfo.containsKey("sweepDuration"), is(true));
        }
    }

    private ServerSessionImpl newServerSession(BayeuxServerImpl bayeux) {
        ServerSessionImpl session = bayeux.newServerSession();
        bayeux.addServerSession(session, bayeux.newMessage());
        session.handshake(null);
        session.connected();
        return session;
    }

    private static int freePort() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("localhost", 0));
            return server.getLocalPort();
        }
    }

    public static class CometDJMXExporter extends HttpServlet {
        private static final AtomicReference<BayeuxServer> BAYEUX = new AtomicReference<>();
        private final List<Object> mbeans = new ArrayList<>();
        private volatile MBeanContainer mbeanContainer;

        @Override
        public void init() {
            mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
            BayeuxServer bayeuxServer = (BayeuxServer)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
            BAYEUX.set(bayeuxServer);
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
