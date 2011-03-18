package org.cometd.javascript.jquery;

import java.io.File;
import java.net.URL;

import org.cometd.javascript.AbstractCometdTest;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.ResourceCollection;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractCometdJQueryTest extends AbstractCometdTest
{
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        File baseDirectory = new File(System.getProperty("basedir", "."));
        File webappDirectory = new File(baseDirectory, "src/main/webapp");
        File overlaidScriptDirectory = new File(baseDirectory, "target/scripts");
        File testResourcesDirectory = new File(baseDirectory, "src/test/resources");
        context.setBaseResource(new ResourceCollection(new String[]
        {
            webappDirectory.getCanonicalPath(),
            overlaidScriptDirectory.getCanonicalPath(),
            testResourcesDirectory.getCanonicalPath()
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
        evaluateURL(new URL(contextURL + "/env.js"));
        evaluateScript("window.location = '" + contextURL + "'");
        evaluateURL(new URL(contextURL + "/org/cometd.js"));
        evaluateURL(new URL(contextURL + "/jquery/jquery-1.5.1.js"));
        evaluateURL(new URL(contextURL + "/jquery/json2.js"));
        evaluateURL(new URL(contextURL + "/jquery/jquery.cometd.js"));
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
