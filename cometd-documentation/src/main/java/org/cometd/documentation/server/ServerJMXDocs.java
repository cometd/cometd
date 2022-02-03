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
package org.cometd.documentation.server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.oort.Oort;
import org.eclipse.jetty.jmx.MBeanContainer;

@SuppressWarnings("unused")
public class ServerJMXDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::jmx[]
    public class CometDJMXExporter extends HttpServlet {
        private final MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());

        @Override
        public void init() throws ServletException {
            BayeuxServer bayeuxServer = (BayeuxServer)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
            mbeanContainer.beanAdded(null, bayeuxServer);

            // Add other components, either CometD ones or your own.
            Oort oort = (Oort)getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
            mbeanContainer.beanAdded(null, oort);
        }

        @Override
        public void service(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
            throw new ServletException();
        }
    }
    // end::jmx[]
}
