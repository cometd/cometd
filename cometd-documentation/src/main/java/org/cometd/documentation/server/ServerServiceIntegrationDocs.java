/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.documentation.server;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.server.BayeuxServer;

@SuppressWarnings("unused")
public class ServerServiceIntegrationDocs {
    static class EchoService {
        public EchoService(BayeuxServer bayeuxServer) {
        }
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::configServlet[]
    public class ConfigurationServlet extends HttpServlet {
        @Override
        public void init() {
            // Grab the Bayeux object
            BayeuxServer bayeux = (BayeuxServer)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
            new EchoService(bayeux);
            // Create other services here

            // This is also the place where you can configure the Bayeux object
            // by adding extensions or specifying a SecurityPolicy
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException {
            // This is a configuration servlet, requests are not serviced.
            throw new ServletException();
        }
    }
    // end::configServlet[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::configListener[]
    public class ConfigurationListener implements ServletContextAttributeListener {
        @Override
        public void attributeAdded(ServletContextAttributeEvent event) {
            if (BayeuxServer.ATTRIBUTE.equals(event.getName())) {
                // Grab the Bayeux object
                BayeuxServer bayeux = (BayeuxServer)event.getValue();
                new EchoService(bayeux);
                // Create other services here

                // This is also the place where you can configure the Bayeux object
                // by adding extensions or specifying a SecurityPolicy
            }
        }

        @Override
        public void attributeRemoved(ServletContextAttributeEvent event) {
        }

        @Override
        public void attributeReplaced(ServletContextAttributeEvent event) {
        }
    }
    // end::configListener[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotationConfigServlet[]
    public class AnnotationConfigurationServlet extends HttpServlet {
        private final List<Object> services = new ArrayList<>();
        private ServerAnnotationProcessor processor;

        @Override
        public void init() {
            // Grab the BayeuxServer object
            BayeuxServer bayeuxServer = (BayeuxServer)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

            // Create the annotation processor
            processor = new ServerAnnotationProcessor(bayeuxServer);

            // Create your annotated service instance and process it
            Object service = new EchoService(bayeuxServer);
            processor.process(service);
            services.add(service);

            // Create other services here

            // This is also the place where you can configure the Bayeux object
            // by adding extensions or specifying a SecurityPolicy
        }

        @Override
        public void destroy() {
            // Deprocess the services that have been created
            for (Object service : services) {
                processor.deprocess(service);
            }
            services.clear();
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException {
            // This is a configuration servlet, requests are not serviced.
            throw new ServletException();
        }
    }
    // end::annotationConfigServlet[]

    static class FooService {
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::anotherServlet[]
    public class AnotherServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            FooService service = (FooService)getServletContext().getAttribute("com.acme.cometd.FooService");
            // Use the foo service here
        }
    }
    // end::anotherServlet[]
}
