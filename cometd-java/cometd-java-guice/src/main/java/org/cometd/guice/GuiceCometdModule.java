package org.cometd.guice;

import com.google.inject.*;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.java.annotation.ServerAnnotationProcessor;
import org.cometd.java.annotation.Service;
import org.cometd.server.BayeuxServerImpl;

/**
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
@Singleton
public class GuiceCometdModule extends AbstractModule implements Provider<BayeuxServerImpl> {
    @Override
    protected final void configure() {
        bind(BayeuxServerImpl.class).toProvider(this);
        bind(BayeuxServer.class).to(BayeuxServerImpl.class);
        // automatically add services
        bindListener(new AbstractMatcher<Object>() {
            public boolean matches(Object o) {
                return o.getClass().isAnnotationPresent(Service.class);
            }
        }, new TypeListener() {
            public <I> void hear(TypeLiteral<I> type, TypeEncounter<I> encounter) {
                final Provider<ServerAnnotationProcessor> processor = encounter.getProvider(ServerAnnotationProcessor.class);
                encounter.register(new InjectionListener<I>() {
                    public void afterInjection(I injectee) {
                        processor.get().process(injectee);
                    }
                });
            }
        });
    }

    public final BayeuxServerImpl get() {
        BayeuxServerImpl server = new BayeuxServerImpl();
        configure(server);
        return server;
    }

    protected void configure(BayeuxServerImpl server) {
    }

    @Provides
    @Singleton
    private ServerAnnotationProcessor annotationProcessor(BayeuxServer server) {
        return new ServerAnnotationProcessor(server);
    }
}
