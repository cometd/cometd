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
package org.cometd.annotation.guice;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.Subscription;
import org.cometd.annotation.server.Configure;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerSession;

@Service // CometD annotation that marks the class as a CometD service
public class GuiceBayeuxService {
    public static final String CHANNEL = "/foo";

    public final Dependency dependency; // Injected by Guice via constructor
    @Inject
    public BayeuxServer bayeuxServer; // Injected by Guice
    public boolean configured;
    public boolean active;
    @Session
    public ServerSession serverSession; // Injected by CometD's annotation processor

    @Inject
    public GuiceBayeuxService(Dependency dependency) {
        this.dependency = dependency;
    }

    @PostConstruct
    public void start() // Invoked by CometD's annotation processor
    {
        if (!configured) {
            throw new IllegalStateException();
        }
        active = true;
    }

    @PreDestroy
    public void stop() // Invoked by CometD's annotation processor
    {
        active = false;
    }

    @Configure(CHANNEL)
    private void configureFoo(ConfigurableServerChannel channel) // Invoked by CometD's annotation processor
    {
        configured = true;
    }

    @Subscription(CHANNEL)
    public void foo(Message message) // Invoked by CometD's annotation processor
    {
    }

    public static class Dependency // Another bean
    {
    }
}
