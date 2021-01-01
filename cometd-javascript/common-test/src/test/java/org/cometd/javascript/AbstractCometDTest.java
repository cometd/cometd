/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.cometd.javascript.jquery.JQueryTestProvider;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

public abstract class AbstractCometDTest {
    @Rule
    public final TestWatcher testName = new TestWatcher() {
        @Override
        public void starting(Description description) {
            super.starting(description);
            String providerClass = getProviderClassName();
            System.err.printf("Running %s.%s() [%s]%n",
                    description.getClassName(),
                    description.getMethodName(),
                    providerClass.substring(providerClass.lastIndexOf('.') + 1));
        }
    };
    private final JavaScriptCookieStore.Store cookieStore = new JavaScriptCookieStore.Store();
    private final Map<String, String> sessionStore = new HashMap<>();
    protected TestProvider provider;
    protected Server server;
    protected ServerConnector connector;
    protected ServletContextHandler context;
    protected CometDServlet cometdServlet;
    protected int metaConnectPeriod = 5000;
    protected String cometdServletPath = "/cometd";
    protected int port;
    protected String contextURL;
    protected String cometdURL;
    protected BayeuxServerImpl bayeuxServer;
    protected int expirationPeriod = 2500;
    protected ThreadModel threadModel;
    private XMLHttpRequestClient xhrClient;
    private WebSocketConnector wsConnector;

    @Before
    public void initCometDServer() throws Exception {
        Map<String, String> options = new HashMap<>();
        initCometDServer(options);
    }

    protected void initCometDServer(Map<String, String> options) throws Exception {
        prepareAndStartServer(options);
        initPage();
    }

    protected void prepareAndStartServer(Map<String, String> options) throws Exception {
        String providerClass = getProviderClassName();
        provider = (TestProvider)Thread.currentThread().getContextClassLoader().loadClass(providerClass).newInstance();

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        WebSocketServerContainerInitializer.configureContext(context);

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup CometD servlet
        String cometdURLMapping = cometdServletPath + "/*";
        cometdServlet = new CometDServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        for (Map.Entry<String, String> entry : options.entrySet()) {
            cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        }
        cometdServletHolder.setInitParameter("timeout", String.valueOf(metaConnectPeriod));
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        customizeContext(context);

        startServer();

        contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometdServletPath;
    }

    protected void startServer() throws Exception {
        connector.setPort(port);
        server.start();
        port = connector.getLocalPort();
        bayeuxServer = cometdServlet.getBayeux();
    }

    @After
    public void destroyCometDServer() throws Exception {
        destroyPage();
        stopServer();
        cookieStore.clear();
    }

    protected void stopServer() throws Exception {
        server.stop();
        server.join();
    }

    private String getProviderClassName() {
        return System.getProperty("toolkitTestProvider", JQueryTestProvider.class.getName());
    }

    protected String getLogLevel() {
        String property = Log.getLogger("org.cometd.javascript").isDebugEnabled() ? "debug" : "info";
        return property.toLowerCase(Locale.ENGLISH);
    }

    protected void customizeContext(ServletContextHandler context) throws Exception {
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

    protected void initPage() throws Exception {
        initJavaScript();
        initCometD();
    }

    protected void initCometD() throws Exception {
        provider.provideCometD(threadModel, contextURL);
    }

    protected void initJavaScript() throws Exception {
        // Initializes the thread model
        org.mozilla.javascript.Context jsContext = org.mozilla.javascript.Context.enter();
        try {
            ScriptableObject rootScope = jsContext.initStandardObjects();

            ScriptableObject.defineClass(rootScope, JavaScriptCookieStore.class);
            jsContext.evaluateString(rootScope, "var cookies = new JavaScriptCookieStore();", "cookies", 1, null);
            JavaScriptCookieStore cookies = (JavaScriptCookieStore)rootScope.get("cookies", rootScope);
            cookies.setStore(cookieStore);

            ScriptableObject.defineClass(rootScope, JavaScriptThreadModel.class);
            jsContext.evaluateString(rootScope, "var threadModel = new JavaScriptThreadModel(this);", "threadModel", 1, null);
            threadModel = (ThreadModel)rootScope.get("threadModel", rootScope);
            threadModel.init();

            ScriptableObject.defineClass(rootScope, XMLHttpRequestClient.class);
            ScriptableObject.defineClass(rootScope, XMLHttpRequestExchange.class);
            jsContext.evaluateString(rootScope, "var xhrClient = new XMLHttpRequestClient(cookies);", "xhrClient", 1, null);
            xhrClient = (XMLHttpRequestClient)rootScope.get("xhrClient", rootScope);
            xhrClient.start();

            ScriptableObject.defineClass(rootScope, WebSocketConnector.class);
            ScriptableObject.defineClass(rootScope, WebSocketConnection.class);
            jsContext.evaluateString(rootScope, "var wsConnector = new WebSocketConnector(cookies);", "wsConnector", 1, null);
            wsConnector = (WebSocketConnector)rootScope.get("wsConnector", rootScope);
            wsConnector.start();

            ScriptableObject.defineClass(rootScope, SessionStorage.class);
            jsContext.evaluateString(rootScope, "var sessionStorage = new SessionStorage();", "sessionStorage", 1, null);
            SessionStorage sessionStorage = (SessionStorage)rootScope.get("sessionStorage", rootScope);
            sessionStorage.setStore(sessionStore);
        } finally {
            org.mozilla.javascript.Context.exit();
        }
    }

    protected void provideTimestampExtension() throws Exception {
        provider.provideTimestampExtension(threadModel, contextURL);
    }

    protected void provideTimesyncExtension() throws Exception {
        provider.provideTimesyncExtension(threadModel, contextURL);
    }

    protected void provideMessageAcknowledgeExtension() throws Exception {
        provider.provideMessageAcknowledgeExtension(threadModel, contextURL);
    }

    protected void provideReloadExtension() throws Exception {
        provider.provideReloadExtension(threadModel, contextURL);
    }

    protected void provideBinaryExtension() throws Exception {
        provider.provideBinaryExtension(threadModel, contextURL);
    }

    protected void destroyPage() throws Exception {
        destroyJavaScript();
    }

    protected void destroyJavaScript() throws Exception {
        if (wsConnector != null) {
            wsConnector.stop();
        }
        if (xhrClient != null) {
            xhrClient.stop();
        }
        if (threadModel != null) {
            threadModel.destroy();
        }
    }

    @SuppressWarnings("unchecked")
    protected <T> T evaluateScript(String script) {
        return evaluateScript(null, script);
    }

    @SuppressWarnings("unchecked")
    protected <T> T evaluateScript(String scriptName, String script) {
        return (T)threadModel.evaluate(scriptName, script);
    }

    protected void defineClass(Class<? extends Scriptable> clazz) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        threadModel.define(clazz);
    }

    @SuppressWarnings("unchecked")
    protected <T> T get(String name) {
        return (T)threadModel.get(name);
    }

    protected void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(x);
        }
    }

    protected void disconnect() throws InterruptedException {
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);
    }
}
