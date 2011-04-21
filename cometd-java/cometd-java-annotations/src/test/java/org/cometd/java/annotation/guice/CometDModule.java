package org.cometd.java.annotation.guice;

import javax.inject.Singleton;

import com.google.inject.AbstractModule;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.BayeuxServerImpl;

public class CometDModule extends AbstractModule
{
    @Override
    protected void configure()
    {
        // Binds but does not call start() yet, since it's not good practice in modules
        // (modules do not have a lifecycle: cannot be stopped)

        // Specifies that the implementation class must be a singleton.
        // This is needed in case a dependency declares to depend on
        // BayeuxServerImpl instead of BayeuxServer so that Guice does
        // not create one instance for BayeuxServer dependencies and
        // one instance for BayeuxServerImpl dependencies.
        bind(BayeuxServerImpl.class).in(Singleton.class);
        // Binds the interface to the implementation class
        bind(BayeuxServer.class).to(BayeuxServerImpl.class);
    }
}
