/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.javascript;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

import org.cometd.javascript.jquery.JQueryTestProvider;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometdServlet;
import org.cometd.websocket.server.WebSocketTransport;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mozilla.javascript.ScriptableObject;

public abstract class AbstractCometDTest
{
    @Rule
    public final TestWatcher testName = new TestWatcher()
    {
        @Override
        public void starting(Description description)
        {
            super.starting(description);
            String providerClass = getProviderClassName();
            System.err.printf("Running %s.%s() [%s]%n",
                    description.getClassName(),
                    description.getMethodName(),
                    providerClass.substring(providerClass.lastIndexOf('.') + 1));
        }
    };
    protected TestProvider provider;
    private HttpCookieStore cookies;
    protected Server server;
    protected Connector connector;
    protected ServletContextHandler context;
    protected CometdServlet cometdServlet;
    protected int longPollingPeriod = 5000;
    protected String cometServletPath = "/cometd";
    protected int port;
    protected String contextURL;
    protected String cometdURL;
    protected BayeuxServerImpl bayeuxServer;
    protected int expirationPeriod = 2500;
    protected ThreadModel threadModel;
    private XMLHttpRequestClient xhrClient;
    private WebSocketClientFactory wsClientFactory;

    @Before
    public void initCometDServer() throws Exception
    {
        String providerClass = getProviderClassName();
        provider = (TestProvider)Thread.currentThread().getContextClassLoader().loadClass(providerClass).newInstance();

        cookies = new HttpCookieStore();

        server = new Server();
        connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup CometD servlet
        cometdServlet = new CometdServlet();
        ServletHolder cometServletHolder = new ServletHolder(cometdServlet);
        cometServletHolder.setInitParameter("timeout", String.valueOf(longPollingPeriod));
        cometServletHolder.setInitParameter("transports", WebSocketTransport.class.getName());
        if (Boolean.getBoolean("debugTests"))
            cometServletHolder.setInitParameter("logLevel", "3");
        context.addServlet(cometServletHolder, cometServletPath + "/*");

        customizeContext(context);

        startServer();

        contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometServletPath;

        initPage();
    }

    protected void startServer() throws Exception
    {
        connector.setPort(port);
        server.start();
        port = connector.getLocalPort();
        bayeuxServer = cometdServlet.getBayeux();
    }

    @After
    public void destroyCometDServer() throws Exception
    {
        destroyPage();
        stopServer();
    }

    protected void stopServer() throws Exception
    {
        server.stop();
        server.join();
        cookies.clear();
    }

    private String getProviderClassName()
    {
        return System.getProperty("toolkitTestProvider", JQueryTestProvider.class.getName());
    }

    protected String getLogLevel()
    {
        return Boolean.getBoolean("debugTests") ? "debug" : "info";
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

    protected void initPage() throws Exception
    {
        initJavaScript();
        initCometD();
    }

    protected void initCometD() throws Exception
    {
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

            ScriptableObject.defineClass(rootScope, WebSocketClientFactory.class);
            ScriptableObject.defineClass(rootScope, WebSocketClient.class);
            jsContext.evaluateString(rootScope, "var wsClientFactory = new WebSocketClientFactory();", "wsClientFactory", 1, null);
            wsClientFactory = (WebSocketClientFactory)rootScope.get("wsClientFactory", rootScope);
            wsClientFactory.start();
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
        wsClientFactory.stop();
        xhrClient.stop();
        threadModel.destroy();
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

    protected void sleep(long time)
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            throw new RuntimeException(x);
        }
    }

    protected void disconnect() throws InterruptedException
    {
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);
    }
}
