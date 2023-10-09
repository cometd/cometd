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
package org.cometd.annotation.server;

import java.util.ArrayList;
import java.util.List;
import jakarta.servlet.ServletException;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.http.jakarta.CometDServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A specialized version of {@link CometDServlet} that can be configured with the init-parameter
 * <b>services</b> to be a comma separated list of class names of annotated services, that will
 * be processed by {@link ServerAnnotationProcessor} upon initialization.</p>
 * <p>
 * A configuration example:
 * <pre>{@code
 * <web-app xmlns="http://java.sun.com/xml/ns/javaee" ...>
 *   <servlet>
 *     <servlet-name>cometd</servlet-name>
 *     <servlet-class>org.cometd.annotation.AnnotationCometDServlet</servlet-class>
 *     <init-param>
 *       <param-name>services</param-name>
 *       <param-value>org.cometd.examples.FooService, org.cometd.examples.BarService</param-value>
 *     </init-param>
 *   </servlet>
 * </web-app>
 * }</pre>
 */
public class AnnotationCometDServlet extends CometDServlet {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnnotationCometDServlet.class);

    private final List<Object> services = new ArrayList<>();
    private volatile ServerAnnotationProcessor processor;

    @Override
    public void init() throws ServletException {
        super.init();

        processor = newServerAnnotationProcessor(getBayeuxServer());

        String servicesParam = getInitParameter("services");
        if (servicesParam != null && servicesParam.length() > 0) {

            for (String serviceClass : servicesParam.split(",")) {
                Object service = processService(processor, serviceClass.trim());
                services.add(service);
                registerService(service);
            }
        }
    }

    protected ServerAnnotationProcessor newServerAnnotationProcessor(BayeuxServer bayeuxServer) {
        return new ServerAnnotationProcessor(bayeuxServer);
    }

    protected Object processService(ServerAnnotationProcessor processor, String serviceClassName) throws ServletException {
        try {
            Object service = newService(serviceClassName);
            processor.process(service);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Processed annotated service {}", service);
            }
            return service;
        } catch (Exception x) {
            LOGGER.warn("Failed to create annotated service " + serviceClassName, x);
            throw new ServletException(x);
        }
    }

    protected Object newService(String serviceClassName) throws Exception {
        Class<?> serviceClass = Thread.currentThread().getContextClassLoader().loadClass(serviceClassName);
        return serviceClass.getConstructor().newInstance();
    }

    protected void registerService(Object service) {
        getServletContext().setAttribute(service.getClass().getName(), service);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Registered annotated service {} in servlet context", service);
        }
    }

    @Override
    public void destroy() {
        for (Object service : services) {
            deregisterService(service);
            deprocessService(processor, service);
        }
        super.destroy();
    }

    protected void deregisterService(Object service) {
        getServletContext().removeAttribute(service.getClass().getName());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Deregistered annotated service {}", service);
        }
    }

    protected void deprocessService(ServerAnnotationProcessor processor, Object service) {
        processor.deprocess(service);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Deprocessed annotated service {}", service);
        }
    }

    public List<Object> getServices() {
        return services;
    }
}
