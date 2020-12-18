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
package org.cometd.tests.spring.annotation;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.http.AsyncJSONTransport;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Spring component that configures CometD services.
 * <p>
 * Spring scans the classes and finds this class annotated with Spring's @Configuration
 * annotation, and makes an instance. Then it notices that it has a bean factory
 * method (annotated with @Bean) that produces the BayeuxServer instance.
 * Note that, as per Spring documentation, this class is subclassed by Spring
 * via CGLIB to invoke bean factory methods only once.
 * <p>
 * Implementing {@link DestructionAwareBeanPostProcessor} allows to plug-in
 * CometD's annotation processor to process the CometD services.
 */
@Configuration
public class Configurator implements DestructionAwareBeanPostProcessor {
    private BayeuxServer bayeuxServer;
    private ServerAnnotationProcessor processor;

    @Inject
    private void setBayeuxServer(BayeuxServer bayeuxServer) {
        this.bayeuxServer = bayeuxServer;
    }

    @PostConstruct
    private void init() {
        this.processor = new ServerAnnotationProcessor(bayeuxServer);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String name) throws BeansException {
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
    public void postProcessBeforeDestruction(Object bean, String name) throws BeansException {
        processor.deprocessCallbacks(bean);
    }

    @Override
    public boolean requiresDestruction(Object bean) {
        return true;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public BayeuxServer bayeuxServer() {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();
        // Don't initialize the WebSocket transports, since for
        // this test we don't have the required ServletContext.
        bayeuxServer.setOption("transports", AsyncJSONTransport.class.getName());
        return bayeuxServer;
    }
}
