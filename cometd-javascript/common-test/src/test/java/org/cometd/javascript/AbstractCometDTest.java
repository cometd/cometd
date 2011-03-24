package org.cometd.javascript;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

import org.cometd.javascript.jquery.JQueryTestProvider;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.junit.After;
import org.junit.Before;
import org.mozilla.javascript.ScriptableObject;

public abstract class AbstractCometDTest
{
    protected TestProvider provider;
    private HttpCookieStore cookies;
    private Server server;
    protected int longPollingPeriod = 5000;
    protected String cometServletPath = "/cometd";
    protected int port;
    protected String contextURL;
    protected String cometdURL;
    protected int expirationPeriod = 2500;
    private ThreadModel threadModel;
    private XMLHttpRequestClient xhrClient;

    @Before
    public void initCometDServer() throws Exception
    {
        String providerClass = System.getProperty("toolkitTestProvider", JQueryTestProvider.class.getName());
        provider = (TestProvider)Thread.currentThread().getContextClassLoader().loadClass(providerClass).newInstance();

        cookies = new HttpCookieStore();

        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup CometD servlet
        CometdServlet cometdServlet = new CometdServlet();
        ServletHolder cometServletHolder = new ServletHolder(cometdServlet);
        cometServletHolder.setInitParameter("timeout", String.valueOf(longPollingPeriod));
        cometServletHolder.setInitParameter("logLevel", "3");
        context.addServlet(cometServletHolder, cometServletPath + "/*");

        customizeContext(context);

        server.start();
        port = connector.getLocalPort();

        contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometServletPath;

        customizeBayeux(cometdServlet.getBayeux());

        initPage();
    }

    @After
    public void destroyCometDServer() throws Exception
    {
        destroyPage();

        server.stop();
        server.join();
        cookies.clear();
    }

    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        File baseDirectory = new File(System.getProperty("basedir", "."));
        File overlaidScriptDirectory = new File(baseDirectory, "target/scripts");
        File mainResourcesDirectory = new File(baseDirectory, "src/main/resources");
        File testResourcesDirectory = new File(baseDirectory, "src/test/resources");
        context.setBaseResource(new ResourceCollection(new String[]
        {
            overlaidScriptDirectory.getCanonicalPath(),
            mainResourcesDirectory.getCanonicalPath(),
            testResourcesDirectory.getCanonicalPath()
        }));
    }

    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
    }

    protected void initPage() throws Exception
    {
        initJavaScript();
        provider.provideCometD(threadModel, contextURL);
    }

    protected void initJavaScript() throws Exception
    {
        // Initializes the thread model
        org.mozilla.javascript.Context jsContext = org.mozilla.javascript.Context.enter();
        try
        {
            ScriptableObject rootScope = jsContext.initStandardObjects();

            ScriptableObject.defineClass(rootScope, HttpCookieStore.class);
            jsContext.evaluateString(rootScope, "var cookies = new HttpCookieStore();", "cookies", 1, null);
            HttpCookieStore cookies = (HttpCookieStore)rootScope.get("cookies", rootScope);
            cookies.putAll(this.cookies);
            this.cookies = cookies;

            ScriptableObject.defineClass(rootScope, JavaScriptThreadModel.class);
            jsContext.evaluateString(rootScope, "var threadModel = new JavaScriptThreadModel(this);", "threadModel", 1, null);
            threadModel = (ThreadModel)rootScope.get("threadModel", rootScope);
            threadModel.init();

            ScriptableObject.defineClass(rootScope, XMLHttpRequestClient.class);
            ScriptableObject.defineClass(rootScope, XMLHttpRequestExchange.class);
            jsContext.evaluateString(rootScope, "var xhrClient = new XMLHttpRequestClient(2);", "xhrClient", 1, null);
            xhrClient = (XMLHttpRequestClient)rootScope.get("xhrClient", rootScope);
            xhrClient.start();
        }
        finally
        {
            org.mozilla.javascript.Context.exit();
        }
    }

    protected void provideTimestampExtension() throws Exception
    {
        provider.provideTimestampExtension(threadModel, contextURL);
    }

    protected void provideTimesyncExtension() throws Exception
    {
        provider.provideTimesyncExtension(threadModel, contextURL);
    }

    protected void provideMessageAcknowledgeExtension() throws Exception
    {
        provider.provideMessageAcknowledgeExtension(threadModel, contextURL);
    }

    protected void provideReloadExtension() throws Exception
    {
        provider.provideReloadExtension(threadModel, contextURL);
    }

    protected void destroyPage() throws Exception
    {
        destroyJavaScript();
    }

    protected void destroyJavaScript() throws Exception
    {
        threadModel.destroy();
        xhrClient.stop();
    }

    @SuppressWarnings("unchecked")
    protected <T> T evaluateScript(String script)
    {
        return (T)evaluateScript(null, script);
    }

    @SuppressWarnings("unchecked")
    protected <T> T evaluateScript(String scriptName, String script)
    {
        return (T)threadModel.evaluate(scriptName, script);
    }

    protected void defineClass(Class clazz) throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        threadModel.define(clazz);
    }

    @SuppressWarnings("unchecked")
    protected <T> T get(String name)
    {
        return (T) threadModel.get(name);
    }
}
