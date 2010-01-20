package org.cometd.javascript.jquery;

import org.mozilla.javascript.JavaScriptException;

/**
 * @version $Revision$ $Date$
 */
public class CometdParametersTest extends AbstractCometdJQueryTest
{
    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        evaluateScript("$.cometd.setLogLevel('debug');");
    }

    public void testConfigureInitMissingURL() throws Exception
    {
        try
        {
            evaluateScript("$.cometd.configure();");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.configure({});");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.init();");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.init({});");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }
    }

    public void testSubscribeMissingChannel()
    {
        try
        {
            evaluateScript("$.cometd.subscribe();");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.subscribe($.cometd, '_debug');");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }
    }

    public void testSubscribeMissingFunction()
    {
        try
        {
            evaluateScript("$.cometd.subscribe('/channel');");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.subscribe('/channel', {});");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.subscribe('/channel', {}, 0);");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }
    }

    public void testUnsubscribeMissingSubscription()
    {
        try
        {
            evaluateScript("$.cometd.unsubscribe();");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.unsubscribe({});");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }
    }

    public void testAddListenerMissingChannel()
    {
        try
        {
            evaluateScript("$.cometd.addListener();");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.addListener($.cometd, '_debug');");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }
    }

    public void testAddListenerMissingFunction()
    {
        try
        {
            evaluateScript("$.cometd.addListener('/channel');");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.addListener('/channel', {});");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.addListener('/channel', {}, 0);");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }
    }

    public void testRemoveListenerMissingSubscription()
    {
        try
        {
            evaluateScript("$.cometd.removeListener();");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.removeListener({});");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }
    }

    public void testPublishMissingChannel()
    {
        try
        {
            evaluateScript("$.cometd.publish();");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }

        try
        {
            evaluateScript("$.cometd.publish({});");
            fail();
        }
        catch (JavaScriptException ignored)
        {
        }
    }
}
