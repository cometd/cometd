package org.cometd.guice;

import com.google.inject.Injector;
import org.cometd.java.annotation.AnnotationCometdServlet;
import org.cometd.java.annotation.ServerAnnotationProcessor;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.util.Loader;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * CometD Servlet to use with Guice Servlet extention.
 * <code><pre>
final class WebModule extends ServletModule {
    protected void configureServlets() {
        bind(CrossOriginFilter.class).in(Singleton.class);
        filter("/*").through(CrossOriginFilter.class);
        serve("/cometd/*").with(GuiceCometdServlet.class);
    }
}
 * </pre></code
 *
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
@Singleton
public final class GuiceCometdServlet extends AnnotationCometdServlet {

    private final Injector injector;

    @Inject
    public GuiceCometdServlet(Injector injector) {
        this.injector = injector;
    }

    @Override
    protected BayeuxServerImpl newBayeuxServer() {
        return injector.getInstance(BayeuxServerImpl.class);
    }

    @Override
    protected ServerAnnotationProcessor newServerAnnotationProcessor() {
        return injector.getInstance(ServerAnnotationProcessor.class);
    }

    @SuppressWarnings({"unchecked"})
    @Override
    protected Object newService(String serviceClassName) throws Exception {
        return injector.getInstance(Loader.loadClass(getClass(), serviceClassName));
    }
}
