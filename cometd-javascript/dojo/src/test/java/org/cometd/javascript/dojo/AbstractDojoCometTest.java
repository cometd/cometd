package org.cometd.javascript.dojo;

import java.io.File;
import java.net.URL;

import org.cometd.AbstractCometTest;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.testng.annotations.BeforeMethod;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractDojoCometTest extends AbstractCometTest
{
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        File baseDirectory = new File(System.getProperty("basedir"));
        File webappDirectory = new File(baseDirectory, "src/main/webapp");
        File overlaidScriptDirectory = new File(baseDirectory, "target/scripts");
        context.setBaseResource(new ResourceCollection(new String[]
        {
            webappDirectory.getCanonicalPath(),
            overlaidScriptDirectory.getCanonicalPath()
        }));
    }

    @BeforeMethod
    public void init() throws Exception
    {
        // Order of the script evaluation is important, as they depend one from the other
        URL envURL = new URL(contextURL + "/env.js");
        evaluateURL(envURL);
        evaluateScript("window.location = '" + contextURL + "'");
        URL dojoURL = new URL(contextURL + "/dojo/dojo.js");
        evaluateURL(dojoURL);
        evaluateScript("dojo.require('dojox.cometd');");
    }
}
