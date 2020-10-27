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
package org.cometd.tests.spring.oort;

import javax.annotation.PostConstruct;
import jakarta.servlet.ServletContext;

import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;
import org.cometd.server.BayeuxServerImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.ServletContextAware;

@Configuration
public class OortConfigurator implements DestructionAwareBeanPostProcessor, ServletContextAware {
    private ServletContext servletContext;
    private ServerAnnotationProcessor processor;

    @PostConstruct
    private void init() {
        BayeuxServer bayeuxServer = bayeuxServer();
        bayeuxServer.setSecurityPolicy(policy());
        this.processor = new ServerAnnotationProcessor(bayeuxServer);

        // Link the cloud, or use OortMulticastConfigurer.
        Oort oort = oort();
//        oort.observeComet("http://cloud.cometd.org/cometd");

        // Observe the required channels.
        oort.observeChannel("/cloud/*");
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

    @Override
    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public BayeuxServer bayeuxServer() {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();
        bayeuxServer.setOption(ServletContext.class.getName(), servletContext);
        bayeuxServer.setOption("ws.cometdURLMapping", "/cometd/*");
        servletContext.setAttribute(BayeuxServer.ATTRIBUTE, bayeuxServer);
        return bayeuxServer;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public Oort oort() {
        Oort oort = new Oort(bayeuxServer(), "http://localhost:8080/cometd");
        servletContext.setAttribute(Oort.OORT_ATTRIBUTE, oort);
        return oort;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public Seti seti() {
        Seti seti = new Seti(oort());
        servletContext.setAttribute(Seti.SETI_ATTRIBUTE, seti);
        return seti;
    }

    @Bean
    public SecurityPolicy policy() {
        return new OortSecurityPolicy(seti());
    }
}
