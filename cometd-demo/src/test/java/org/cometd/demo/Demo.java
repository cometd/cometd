/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.demo;

import java.lang.management.ManagementFactory;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

public class Demo {
    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final String CONTEXT_PATH = "/app";

    public static void main(String[] args) throws Exception {
        new Demo(HTTP_PORT, HTTPS_PORT, CONTEXT_PATH).start();
    }

    private final int httpPort;
    private final int httpsPort;
    private final String contextPath;

    public Demo(int httpPort, int httpsPort, String contextPath) {
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
        this.contextPath = contextPath;
    }

    public Server start() throws Exception {
        // NOTE: this code is referenced by the documentation

        // tag::embedded-cometd[]
        // Setup and configure the thread pool.
        QueuedThreadPool threadPool = new QueuedThreadPool();

        // The Jetty Server instance.
        Server server = new Server(threadPool);

        // Setup and configure a connector for clear-text http:// and ws://.
        HttpConfiguration httpConfig = new HttpConfiguration();
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(httpPort);
        server.addConnector(connector);

        // Setup and configure a connector for https:// and wss://.
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStoreType("pkcs12");
        sslContextFactory.setKeyStorePassword("storepwd");
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        ServerConnector tlsConnector = new ServerConnector(server, sslContextFactory, new HttpConnectionFactory(httpsConfig));
        tlsConnector.setPort(httpsPort);
        server.addConnector(tlsConnector);

        // The context where the application is deployed.
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        // Configure WebSocket for the context.
        WebSocketServerContainerInitializer.configureContext(context);

        // Setup JMX.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);
        context.setInitParameter(ServletContextHandler.MANAGED_ATTRIBUTES, BayeuxServer.ATTRIBUTE);

        // Setup the default servlet to serve static files.
        context.addServlet(DefaultServlet.class, "/");

        // Setup the CometD servlet.
        String cometdURLMapping = "/cometd/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        context.addServlet(cometdServletHolder, cometdURLMapping);
        // Required parameter for WebSocket transport configuration.
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        // Optional parameter for BayeuxServer configuration.
        cometdServletHolder.setInitParameter("timeout", String.valueOf(15000));
        // Start the CometD servlet eagerly to show up in JMX.
        cometdServletHolder.setInitOrder(1);

        // Add your own listeners/filters/servlets here.

        server.start();
        // end::embedded-cometd[]

        return server;
    }
}
