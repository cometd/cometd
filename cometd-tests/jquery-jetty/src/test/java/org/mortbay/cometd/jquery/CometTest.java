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

package org.mortbay.cometd.jquery;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;

import org.cometd.Bayeux;
import org.mortbay.cometd.JavaScriptThreadModel;
import org.mortbay.cometd.ThreadModel;
import org.mortbay.cometd.XMLHttpRequestClient;
import org.mortbay.cometd.XMLHttpRequestExchange;
import org.mortbay.cometd.continuation.ContinuationCometdServlet;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.HandlerCollection;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.resource.ResourceCollection;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptableObject;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

/**
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public class CometTest
{
    private ThreadModel threadModel;
    private Server server;
    protected int port;
    protected String contextURL;
    protected String cometServletPath = "/cometd";
    protected String cometURL;
    protected int longPollingPeriod = 5000;

    @BeforeMethod
    public void startComet() throws Exception
    {
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        Context context = new Context(handlers, contextPath, Context.SESSIONS);

        File jqryDirectory = new File(System.getProperty("jqrydir","../../cometd-jquery/examples"));
        File dojoDirectory = new File(System.getProperty("dojodir","../../cometd-dojox/examples"));
        File demoDirectory = new File(System.getProperty("demodir","../../cometd-jetty/demo"));
        File baseDirectory = new File(System.getProperty("basedir","."));
        context.setBaseResource(new ResourceCollection(new String[]
        {
            new File(baseDirectory, "src/test/resources").getCanonicalPath(),
            new File(demoDirectory, "src/main/webapp").getCanonicalPath(),
            new File(dojoDirectory, "src/main/webapp").getCanonicalPath(),
            new File(jqryDirectory, "src/main/webapp").getCanonicalPath()
        }));

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup comet servlet
        ContinuationCometdServlet cometServlet = new ContinuationCometdServlet();
        ServletHolder cometServletHolder = new ServletHolder(cometServlet);
        cometServletHolder.setInitParameter("timeout", String.valueOf(longPollingPeriod));
        cometServletHolder.setInitParameter("loglevel","2");
        context.addServlet(cometServletHolder, cometServletPath +"/*");

        // Setup bayeux listener
        context.addEventListener(new BayeuxInitializer());

        customizeContext(context);

        server.start();
        port = connector.getLocalPort();

        contextURL = "http://localhost:" + port + contextPath;
        cometURL = contextURL + cometServletPath;

        // Initializes the threading model
        org.mozilla.javascript.Context jsContext = org.mozilla.javascript.Context.enter();
        try
        {
            ScriptableObject rootScope = jsContext.initStandardObjects();
            ScriptableObject.defineClass(rootScope, JavaScriptThreadModel.class);
            jsContext.evaluateString(rootScope, "var threadModel = new JavaScriptThreadModel(this);", "threadModel", 1, null);
            threadModel = (ThreadModel)rootScope.get("threadModel", rootScope);
            threadModel.init();
        }
        finally
        {
            org.mozilla.javascript.Context.exit();
        }

        threadModel.evaluate("var maxConnections = " + getMaxConnections() + ";");
        threadModel.define(XMLHttpRequestClient.class);
        threadModel.define(XMLHttpRequestExchange.class);
        URL envURL = new URL(contextURL + "/env.js");
        threadModel.evaluate(envURL);
        threadModel.evaluate("window.location = '" + contextURL + "'");
        URL json2URL = new URL(contextURL + "/jquery/json2.js");
        threadModel.evaluate(json2URL);
        URL jqueryURL = new URL(contextURL + "/jquery/jquery.js");
        threadModel.evaluate(jqueryURL);
        URL jqueryCometURL = new URL(contextURL + "/jquery/jquery.cometd.js");
        threadModel.evaluate(jqueryCometURL);
    }

    /**
     * @return the max number of connections that the simulated browser environment can open
     * for each different server address (default is 2).
     */
    protected int getMaxConnections()
    {
        return 2;
    }

    @AfterMethod(alwaysRun = true)
    public void stopComet() throws Exception
    {
        threadModel.destroy();
        server.stop();
        server.join();
    }

    protected void customizeContext(Context context)
    {
    }

    protected void customizeBayeux(Bayeux bayeux)
    {
    }

    protected void evaluateURL(URL script) throws IOException
    {
        threadModel.evaluate(script);
    }

    protected <T> T evaluateScript(String script)
    {
        return (T)threadModel.evaluate(script);
    }

    protected void defineClass(Class clazz) throws InvocationTargetException, InstantiationException, IllegalAccessException
    {
        threadModel.define(clazz);
    }

    protected <T> T get(String name)
    {
        return (T)threadModel.get(name);
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
        if (jsObject instanceof NativeArray) return convertArray((NativeArray)jsObject);
        if (jsObject instanceof NativeObject) return convertObject((NativeObject)jsObject);
        if (jsObject instanceof NativeJavaObject) return ((NativeJavaObject)jsObject).unwrap();
        return jsObject;
    }

    private static Object[] convertArray(NativeArray jsArray)
    {
        Object[] ids = jsArray.getIds();
        Object[] result = new Object[ids.length];
        for (int i = 0; i < ids.length; i++)
        {
            Object id = ids[i];
            int index = (Integer)id;
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
                Object jsValue = jsObject.get((String)id, jsObject);
                result.put(id, jsToJava(jsValue));
            }
            else if (id instanceof Integer)
            {
                Object jsValue = jsObject.get((Integer)id, jsObject);
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
            if (event.getName().equals(Bayeux.DOJOX_COMETD_BAYEUX))
            {
                Bayeux bayeux=(Bayeux)event.getValue();
                customizeBayeux(bayeux);
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
