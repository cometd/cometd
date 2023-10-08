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
import java.net.URI;
import java.util.List;

import org.cometd.server.http.jakarta.CometDServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * Main class for cometd demo.
 * This is of use when running demo in a terracotta cluster
 */
public class OortDemo {
    private final Server _server;

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        OortDemo demo = new OortDemo(port);
        demo._server.join();
    }

    public OortDemo(int port) throws Exception {
        String base = ".";

        QueuedThreadPool qtp = new QueuedThreadPool();
        qtp.setMinThreads(5);
        qtp.setMaxThreads(200);

        // Manually contruct context to avoid hassles with webapp classloaders for now.
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
        context.addServlet("org.eclipse.jetty.servlet.DefaultServlet", "/");

        context.setBaseResource(ResourceFactory.of(_server).newResource(List.of(
                URI.create(base + "/../../cometd-demo/src/main/webapp/"),
                URI.create(base + "/../../cometd-demo/target/cometd-demo-2.4.0-SNAPSHOT/")
        )));

        ServletHolder cometd_holder = new ServletHolder(CometDServlet.class);
        cometd_holder.setInitParameter("timeout", "200000");
        cometd_holder.setInitParameter("interval", "100");
        cometd_holder.setInitParameter("maxInterval", "100000");
        cometd_holder.setInitParameter("multiSessionInterval", "1500");
        cometd_holder.setInitOrder(1);
        context.addServlet(cometd_holder, "/cometd/*");

        ServletHolder oort_holder = new ServletHolder(OortMulticastConfigServlet.class);
        oort_holder.setInitParameter(OortMulticastConfigServlet.OORT_URL_PARAM, "http://localhost:" + port + "/cometd");
        oort_holder.setInitParameter(OortMulticastConfigServlet.OORT_CHANNELS_PARAM, "/chat/**");
        oort_holder.setInitOrder(2);
        context.addServlet(oort_holder, "/oort/*");

        ServletHolder seti_holder = new ServletHolder(SetiServlet.class);
        seti_holder.setInitOrder(2);
        context.addServlet(seti_holder, "/seti/*");

        ServletHolder demo_holder = new ServletHolder(OortDemoServlet.class);
        demo_holder.setInitOrder(3);
        context.getServletHandler().addServlet(demo_holder);

        context.setInitParameter("org.eclipse.jetty.server.context.ManagedAttributes", "org.cometd.bayeux,org.cometd.oort.Oort");

        _server.start();

        Oort oort = (Oort)context.getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
        assert (oort != null);
    }
}
