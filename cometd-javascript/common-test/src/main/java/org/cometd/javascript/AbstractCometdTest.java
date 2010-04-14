// ========================================================================
// Copyright 2004-2008 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cometd.javascript;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;

import junit.framework.TestCase;
import org.cometd.Bayeux;
import org.cometd.server.AbstractBayeux;
import org.cometd.server.continuation.ContinuationCometdServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public abstract class AbstractCometdTest extends TestCase
{
    private ThreadModel threadModel;
    private Server server;
    protected int port;
    protected String contextURL;
    protected String cometServletPath = "/cometd";
    protected String cometdURL;
    protected int longPollingPeriod = 5000;
    protected int expirationPeriod = 2500;
    private HttpCookieStore cookies;

    @Override
    protected void setUp() throws Exception
    {
        cookies = new HttpCookieStore();
        initCometServer();
    }

    public void initCometServer() throws Exception
    {
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup comet servlet
        ContinuationCometdServlet cometServlet = new ContinuationCometdServlet();
        ServletHolder cometServletHolder = new ServletHolder(cometServlet);
        cometServletHolder.setInitParameter("timeout", String.valueOf(longPollingPeriod));
        cometServletHolder.setInitParameter("logLevel", "2");
        cometServletHolder.setInitParameter("requestAvailable", "true");
        cometServletHolder.setInitParameter("maxInterval", String.valueOf(expirationPeriod));
        context.addServlet(cometServletHolder, cometServletPath + "/*");

        // Setup bayeux listener
        context.addEventListener(new BayeuxInitializer());

        customizeContext(context);

        server.start();
        port = connector.getLocalPort();

        contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometServletPath;
    }

    public void initJavaScript() throws Exception
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
        }
        finally
        {
            org.mozilla.javascript.Context.exit();
        }

        threadModel.evaluate(null, "var maxConnections = " + getMaxConnections() + ";");
        threadModel.define(XMLHttpRequestClient.class);
        threadModel.define(XMLHttpRequestExchange.class);
    }

    /**
     * @return the max number of connections that the simulated browser environment can open
     *         for each different server address (default is 2).
     */
    protected int getMaxConnections()
    {
        return 2;
    }

    @Override
    protected void tearDown() throws Exception
    {
        destroyCometServer();
        cookies.clear();
    }

    public void destroyJavaScript() throws Exception
    {
        threadModel.destroy();
    }

    public void destroyCometServer() throws Exception
    {
        server.stop();
        server.join();
    }

    protected abstract void customizeContext(ServletContextHandler context) throws Exception;

    protected void customizeBayeux(AbstractBayeux bayeux)
    {
    }

    protected void evaluateURL(URL script) throws IOException
    {
        threadModel.evaluate(script);
    }

    protected <T> T evaluateScript(String script)
    {
        return (T)evaluateScript(null, script);
    }

    protected <T> T evaluateScript(String scriptName, String script)
    {
        return (T)threadModel.evaluate(scriptName, script);
    }

    protected void defineClass(Class clazz) throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        threadModel.define(clazz);
    }

    protected <T> T get(String name)
    {
        return (T) threadModel.get(name);
    }

    public static Object jsToJava(Object jsObject)
    {
        if (jsObject == null) return null;
        if (jsObject == org.mozilla.javascript.Context.getUndefinedValue()) return null;
        if (jsObject instanceof String) return jsObject;
        if (jsObject instanceof Boolean) return jsObject;
        if (jsObject instanceof Integer) return jsObject;
        if (jsObject instanceof Long) return jsObject;
        if (jsObject instanceof Float) return jsObject;
        if (jsObject instanceof Double) return jsObject;
        if (jsObject instanceof NativeArray) return convertArray((NativeArray) jsObject);
        if (jsObject instanceof NativeObject) return convertObject((NativeObject) jsObject);
        if (jsObject instanceof NativeJavaObject) return ((NativeJavaObject) jsObject).unwrap();
        return jsObject;
    }

    private static Object[] convertArray(NativeArray jsArray)
    {
        Object[] ids = jsArray.getIds();
        Object[] result = new Object[ids.length];
        for (int i = 0; i < ids.length; i++)
        {
            Object id = ids[i];
            int index = (Integer) id;
            Object jsValue = jsArray.get(index, jsArray);
            result[i] = jsToJava(jsValue);
        }
        return result;
    }

    private static Object convertObject(NativeObject jsObject)
    {
        Object[] ids = jsObject.getIds();
        Map result = new HashMap(ids.length);
        for (Object id : ids)
        {
            if (id instanceof String)
            {
                Object jsValue = jsObject.get((String) id, jsObject);
                result.put(id, jsToJava(jsValue));
            }
            else if (id instanceof Integer)
            {
                Object jsValue = jsObject.get((Integer) id, jsObject);
                result.put(id, jsToJava(jsValue));
            }
            else
                throw new AssertionError();
        }
        return result;
    }

    private class BayeuxInitializer implements ServletContextAttributeListener
    {
        public void attributeAdded(ServletContextAttributeEvent event)
        {
            if (event.getName().equals(Bayeux.ATTRIBUTE))
            {
                Bayeux bayeux = (Bayeux) event.getValue();
                customizeBayeux((AbstractBayeux)bayeux);
            }
        }

        public void attributeRemoved(ServletContextAttributeEvent event)
        {
        }

        public void attributeReplaced(ServletContextAttributeEvent event)
        {
        }
    }
}
