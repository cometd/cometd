/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import java.net.CookieStore;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jdk.nashorn.api.scripting.JSObject;

import org.cometd.javascript.jquery.JQueryTestProvider;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

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
    private final CookieStore cookieStore = new HttpCookieStore() {
        @Override
        public boolean removeAll() {
            return false;
        }
    };
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
    protected JavaScript javaScript;
    private XMLHttpRequestClient xhrClient;
    private WebSocketConnector wsConnector;
    private ScheduledExecutorService executor;

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
        cookieStore.removeAll();
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

    protected void initJavaScript() throws Exception {
        javaScript = new JavaScript();
        javaScript.init();
        executor = Executors.unconfigurableScheduledExecutorService(
                Executors.newScheduledThreadPool(1, 
                        Executors.privilegedThreadFactory()));
        javaScript.putAsync("_scheduler", executor);
        javaScript.evaluate(getClass().getResource("/browser.js"));
        ((JSObject)javaScript.getAsync("window")).setMember("location", contextURL);
        JavaScriptCookieStore cookies = javaScript.getAsync("cookies");
        cookies.setStore(cookieStore);
        xhrClient = javaScript.getAsync("xhrClient");
        xhrClient.start();
        wsConnector = javaScript.getAsync("wsConnector");
        wsConnector.start();
        SessionStorage sessionStorage = javaScript.getAsync("sessionStorage");
        sessionStorage.setStore(sessionStore);
    }

    protected void initCometD() throws Exception {
        provider.provideCometD(javaScript, contextURL);
    }

    protected void provideTimestampExtension() throws Exception {
        provider.provideTimestampExtension(javaScript, contextURL);
    }

    protected void provideTimesyncExtension() throws Exception {
        provider.provideTimesyncExtension(javaScript, contextURL);
    }

    protected void provideMessageAcknowledgeExtension() throws Exception {
        provider.provideMessageAcknowledgeExtension(javaScript, contextURL);
    }

    protected void provideReloadExtension() throws Exception {
        provider.provideReloadExtension(javaScript, contextURL);
    }

    protected void provideBinaryExtension() throws Exception {
        provider.provideBinaryExtension(javaScript, contextURL);
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
        if (javaScript != null) {
            javaScript.destroy();
            javaScript = null;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    @SuppressWarnings("unchecked")
    protected <T> T evaluateScript(String script) {
        return evaluateScript(null, script);
    }

    @SuppressWarnings("unchecked")
    protected <T> T evaluateScript(String scriptName, String script) {
        return (T)javaScript.evaluate(scriptName, script);
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
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);
    }
}
