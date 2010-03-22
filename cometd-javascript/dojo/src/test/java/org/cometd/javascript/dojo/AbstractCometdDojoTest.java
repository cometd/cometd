package org.cometd.javascript.dojo;

import java.io.File;
import java.net.URL;

import org.cometd.javascript.AbstractCometdTest;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.ResourceCollection;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractCometdDojoTest extends AbstractCometdTest
{
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        File baseDirectory = new File(System.getProperty("basedir", "."));
        File webappDirectory = new File(baseDirectory, "src/main/webapp");
        File overlaidScriptDirectory = new File(baseDirectory, "target/scripts");
        context.setBaseResource(new ResourceCollection(new String[]
        {
            webappDirectory.getCanonicalPath(),
            overlaidScriptDirectory.getCanonicalPath()
        }));
    }

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        initPage();
    }

    public void initPage() throws Exception
    {
        initJavaScript();

        // Order of the script evaluation is important, as they depend one from the other
        URL envURL = new URL(contextURL + "/env.js");
        evaluateURL(envURL);
        evaluateScript("window.location = '" + contextURL + "'");
        evaluateURL(new URL(contextURL + "/dojo/dojo.js.uncompressed.js"));
        evaluateURL(new URL(contextURL + "/dojo/io/script.js"));
        evaluateURL(new URL(contextURL + "/org/cometd.js"));
        evaluateURL(new URL(contextURL + "/dojox/cometd.js"));
        evaluateScript("dojo.require('dojox.cometd');");
    }

    @Override
    protected void tearDown() throws Exception
    {
        super.tearDown();
        destroyPage();
    }

    public void destroyPage() throws Exception
    {
        destroyJavaScript();
    }
}
