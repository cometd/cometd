package org.cometd.java.annotation.guice;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.java.annotation.Configure;
import org.cometd.java.annotation.Service;
import org.cometd.java.annotation.Session;
import org.cometd.java.annotation.Subscription;

@Service // CometD annotation that marks the class as a CometD service
public class GuiceBayeuxService
{
    public static final String CHANNEL = "/foo";

    public final Dependency dependency; // Injected by Guice via constructor
    @Inject
    public BayeuxServer bayeuxServer; // Injected by Guice
    public boolean configured;
    public boolean active;
    @Session
    public ServerSession serverSession; // Injected by CometD's annotation processor

    @Inject
    public GuiceBayeuxService(Dependency dependency)
    {
        this.dependency = dependency;
    }

    @PostConstruct
    public void start() // Invoked by CometD's annotation processor
    {
        if (!configured)
            throw new IllegalStateException();
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
