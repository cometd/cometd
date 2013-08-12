/*
 * Copyright (c) 2010 the original author or authors.
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

package com.webtide.demo.auction;

import org.cometd.oort.Oort;
import org.cometd.oort.OortStaticConfigServlet;
import org.cometd.oort.SetiServlet;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.webtide.demo.auction.AuctionServlet;

/**
 * Main class for cometd demo.
 * This is of use when running demo in a terracotta cluster
 */
public class AuctionDemo
{
    public static void main(String[] args) throws Exception
    {
        new AuctionDemo(8080);
    }

    public AuctionDemo(int port) throws Exception
    {
        String base = ".";

        QueuedThreadPool qtp = new QueuedThreadPool();
        qtp.setMinThreads(5);
        qtp.setMaxThreads(200);
        Server server = new Server(qtp);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ServletContextHandler context = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);

        context.setBaseResource(new ResourceCollection(
                Resource.newResource(base + "/src/main/webapp/"),
                Resource.newResource(base + "/target/cometd-demo-2.0.beta1-SNAPSHOT/"))
        );

        // CometD servlet
        ServletHolder cometd_holder = new ServletHolder(CometDServlet.class);
        cometd_holder.setInitParameter("timeout", "200000");
        cometd_holder.setInitParameter("interval", "100");
        cometd_holder.setInitParameter("maxInterval", "100000");
        cometd_holder.setInitParameter("multiSessionInterval", "1500");
        cometd_holder.setInitOrder(1);
        context.addServlet(cometd_holder, "/cometd/*");

        ServletHolder oort_holder = new ServletHolder(OortStaticConfigServlet.class);
        oort_holder.setInitParameter(OortStaticConfigServlet.OORT_URL_PARAM, "http://localhost:" + port + "/cometd");
        oort_holder.setInitParameter(OortStaticConfigServlet.OORT_CLOUD_PARAM, "");
        // oort_holder.setInitParameter(OortStaticConfigServlet.OORT_CLOUD_PARAM,(port==8080)?"http://localhost:"+8081+"/cometd":"http://localhost:"+8080+"/cometd");
        oort_holder.setInitOrder(2);
        context.addServlet(oort_holder, "/oort/*");

        ServletHolder seti_holder = new ServletHolder(SetiServlet.class);
        seti_holder.setInitOrder(2);
        context.addServlet(seti_holder, "/seti/*");

        ServletHolder demo_holder = new ServletHolder(AuctionServlet.class);
        demo_holder.setInitOrder(3);
        context.getServletHandler().addServlet(demo_holder);

        context.addServlet("org.eclipse.jetty.servlet.DefaultServlet", "/");

        server.start();

        Oort oort = (Oort)context.getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
        assert (oort != null);
    }
}
