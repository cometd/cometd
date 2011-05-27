package org.cometd.guice;

import com.google.inject.Injector;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.java.annotation.AnnotationCometdServlet;
import org.cometd.java.annotation.ServerAnnotationProcessor;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.util.Loader;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletException;

/**
 * CometD Servlet to use with Guice Servlet extention.
 *
 * <code><pre>
 * final class WebModule extends ServletModule {
 *     protected void configureServlets() {
 *         bind(CrossOriginFilter.class).in(Singleton.class);
 *         filter(&quot;/*&quot;).through(CrossOriginFilter.class);
 *         serve(&quot;/cometd/*&quot;).with(GuiceCometdServlet.class);
 *     }
 * }
 * </pre></code>
 *
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 * @since 2.2.1
 */
@Singleton
public final class GuiceCometdServlet extends AnnotationCometdServlet {

    private final Injector injector;

    @Inject
    public GuiceCometdServlet(Injector injector) {
        this.injector = injector;
    }

    @Override
    public void init() throws ServletException {
        getServletContext().setAttribute(BayeuxServer.ATTRIBUTE, newBayeuxServer());
        super.init();
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
