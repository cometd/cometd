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
        URL envURL = new URL(contextURL + "/env.js");
        evaluateURL(envURL);
        evaluateScript("window.location = '" + contextURL + "'");
        URL cometdURL = new URL(contextURL + "/org/cometd.js");
        evaluateURL(cometdURL);
        URL jqueryURL = new URL(contextURL + "/jquery/jquery-1.4.2.js");
        evaluateURL(jqueryURL);
        URL jqueryJSONURL = new URL(contextURL + "/jquery/jquery.json-2.2.js");
        evaluateURL(jqueryJSONURL);
        URL jqueryCometdURL = new URL(contextURL + "/jquery/jquery.cometd.js");
        evaluateURL(jqueryCometdURL);
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
