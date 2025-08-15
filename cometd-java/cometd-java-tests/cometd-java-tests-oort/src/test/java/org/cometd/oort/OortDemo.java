/*
 * Copyright (c) 2008 the original author or authors.
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.oort.jakarta.OortMulticastConfigServlet;
import org.cometd.oort.jakarta.SetiServlet;
import org.cometd.server.http.jakarta.CometDServlet;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * Main class for the CometD Oort demo.
 * This is of use when running in a cluster.
 */
public class OortDemo {
    private final Server _server;

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        OortDemo demo = new OortDemo(port);
        demo._server.join();
    }

    public OortDemo(int port) throws Exception {
        QueuedThreadPool qtp = new QueuedThreadPool();
        qtp.setMinThreads(5);
        qtp.setMaxThreads(200);

        // Manually construct context to avoid hassles with webapp classloaders for now.
        _server = new Server(qtp);

        // Setup JMX
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        _server.addBean(mbeanContainer);

        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(port);
        _server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler context = new ServletContextHandler("/", ServletContextHandler.SESSIONS);
        contexts.addHandler(context);
        context.addServlet(DefaultServlet.class, "/");

        try (Stream<Path> demoTargetPaths = Files.list(Paths.get("../../../cometd-demo/target"))) {
            Path path = demoTargetPaths
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("cometd-demo-"))
                    .findFirst()
                    .orElseThrow();
            context.setBaseResource(ResourceFactory.of(_server).newResource(List.of(path.toUri())));
        }

        ServletHolder cometdHolder = new ServletHolder(CometDServlet.class);
        cometdHolder.setInitParameter("timeout", "20000");
        cometdHolder.setInitParameter("allowedTransports", "long-polling");
        cometdHolder.setInitOrder(1);
        context.addServlet(cometdHolder, "/cometd/*");

        ServletHolder oortHolder = new ServletHolder(OortMulticastConfigServlet.class);
        oortHolder.setInitParameter(OortMulticastConfigServlet.OORT_URL_PARAM, "http://localhost:" + port + "/cometd");
        oortHolder.setInitParameter(OortMulticastConfigServlet.OORT_CHANNELS_PARAM, "/chat/**");
        oortHolder.setInitOrder(2);
        context.addServlet(oortHolder, "/oort/*");

        ServletHolder setiHolder = new ServletHolder(SetiServlet.class);
        setiHolder.setInitOrder(2);
        context.addServlet(setiHolder, "/seti/*");

        ServletHolder demo_holder = new ServletHolder(OortDemoServlet.class);
        demo_holder.setInitOrder(3);
        context.getServletHandler().addServlet(demo_holder);

        String names = String.join(",", BayeuxServer.ATTRIBUTE, Oort.OORT_ATTRIBUTE, Seti.SETI_ATTRIBUTE);
        context.setInitParameter(ServletContextHandler.MANAGED_ATTRIBUTES, names);

        _server.start();

        Oort oort = (Oort)context.getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
        assert oort != null;
    }
}
