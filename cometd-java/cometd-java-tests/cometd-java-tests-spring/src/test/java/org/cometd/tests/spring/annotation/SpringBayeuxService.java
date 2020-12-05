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
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.Subscription;
import org.cometd.annotation.server.Configure;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerSession;

@Singleton // Specifies to Spring that this is a singleton
@Named // Spring looks for this annotation when scanning the classes to determine if it's a spring bean
@Service // CometD annotation that marks the class as a CometD service
public class SpringBayeuxService {
    public static final String CHANNEL = "/foo";

    public final Dependency dependency; // Injected by Spring via constructor
    @Inject
    public BayeuxServer bayeuxServer; // Injected by Spring
    public boolean configured;
    public boolean active;
    @Session
    public ServerSession serverSession; // Injected by CometD's annotation processor

    @Inject
    public SpringBayeuxService(Dependency dependency) // Injected by Spring
    {
        this.dependency = dependency;
    }

    @PostConstruct
    public void start() // Initialization method invoked by Spring
    {
        if (!configured) {
            throw new IllegalStateException();
        }
        active = true;
    }

    @PreDestroy
    public void stop() // Destruction method invoked by Spring
    {
        active = false;
    }

    @Configure(CHANNEL)
    private void configureFoo(ConfigurableServerChannel channel) {
        configured = true;
    }

    @Subscription(CHANNEL)
    public void foo(Message message) // Subscription method detected by CometD's annotation processor
    {
    }

    @Singleton
    @Named
    public static class Dependency // Another Spring bean
    {
    }
}
