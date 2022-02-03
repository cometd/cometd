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

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

@SuppressWarnings("unused")
public class ServerServiceIntegrationSpringDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::service[]
    @jakarta.inject.Named // Tells Spring that this is a bean
    @jakarta.inject.Singleton // Tells Spring that this is a singleton
    @Service("echoService")
    public class EchoService {
        @Inject
        private BayeuxServer bayeux;
        @Session
        private ServerSession serverSession;

        @PostConstruct
        public void init() {
            System.out.println("Echo Service Initialized");
        }

        @Listener("/echo")
        public void echo(ServerSession remote, ServerMessage.Mutable message) {
            String channel = message.getChannel();
            Object data = message.getData();
            remote.deliver(serverSession, channel, data, Promise.noop());
        }
    }
    // end::service[]

/*
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::configurer[]
    @Configuration
    public class Configurer implements DestructionAwareBeanPostProcessor, ServletContextAware {
        private ServletContext servletContext;
        private ServerAnnotationProcessor processor;

        @Bean(initMethod = "start", destroyMethod = "stop")
        public BayeuxServer bayeuxServer() {
            BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();
            // Configure BayeuxServer here.
            bayeuxServer.setOption(AbstractServerTransport.TIMEOUT_OPTION, 15000);

            // The following lines are required by WebSocket transports.
            bayeuxServer.setOption("ws.cometdURLMapping", "/cometd/*");
            bayeuxServer.setOption(ServletContext.class.getName(), servletContext);

            // Export this instance to the ServletContext so
            // that CometDServlet can discover it and use it.
            servletContext.setAttribute(BayeuxServer.ATTRIBUTE, bayeuxServer);
            return bayeuxServer;
        }

        @PostConstruct
        private void init() {
            // Creation of BayeuxServer must happen *after*
            // Spring calls setServletContext(ServletContext).
            BayeuxServer bayeuxServer = bayeuxServer();
            this.processor = new ServerAnnotationProcessor(bayeuxServer);
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String name) throws BeansException {
            // Intercept bean initialization and process CometD annotations.
            processor.processDependencies(bean);
            processor.processConfigurations(bean);
            processor.processCallbacks(bean);
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
            return bean;
        }

        @Override
        public boolean requiresDestruction(Object bean) {
            return true;
        }

        @Override
        public void postProcessBeforeDestruction(Object bean, String name) throws BeansException {
            processor.deprocessCallbacks(bean);
        }

        @Override
        public void setServletContext(ServletContext servletContext) {
            this.servletContext = servletContext;
        }
    }
    // end::configurer[]
*/
}

/*
// tag::boot[]
@SpringBootApplication // <1>
class CometDApplication implements ServletContextInitializer { // <2>
    public static void main(String[] args) {
        SpringApplication.run(CometDApplication.class, args);
    }

    @Override
    public void onStartup(ServletContext servletContext) {
        ServletRegistration.Dynamic cometdServlet = servletContext.addServlet("cometd", AnnotationCometDServlet.class); // <3>
        cometdServlet.addMapping("/cometd/*");
        cometdServlet.setAsyncSupported(true);
        cometdServlet.setLoadOnStartup(1);
        cometdServlet.setInitParameter("services", EchoService.class.getName());
        // Possible additional CometD Servlet configuration.
    }
}
// end::boot[]
*/
