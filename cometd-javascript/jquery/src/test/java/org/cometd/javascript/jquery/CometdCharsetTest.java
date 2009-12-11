package org.cometd.javascript.jquery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdCharsetTest extends AbstractCometdJQueryTest
{
    public void testCharset() throws Exception
    {
        String cyrillic = "\u0436"; // zhe
        String greek = "\u0398"; // theta
        String hebrew = "\u05d0"; // aleph
        String arabic = "\u0644"; // lam
        String hiragana = "\u3068"; // to
        String chinese = "\u2ee2"; // cjk's horse

        defineClass(Latch.class);
        evaluateScript("$.cometd.init({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateScript("var data = undefined;");
        evaluateScript("$.cometd.subscribe('/echo', function(message) { data = message.data; latch.countDown(); });");
        evaluateScript("$.cometd.publish('/echo', {" +
                "cyrillic: '" + cyrillic + "'," +
                "greek: '" + greek + "'," +
                "hebrew: '" + hebrew + "'," +
                "arabic: '" + arabic + "'," +
                "hiragana: '" + hiragana + "'," +
                "chinese: '" + chinese + "'" +
                "});");
        assertTrue(latch.await(1000));
        evaluateScript("window.assert(data !== undefined, 'data is undefined');");
        evaluateScript("window.assert(data.cyrillic === '" + cyrillic + "', 'bad cyrillic');");
        evaluateScript("window.assert(data.greek === '" + greek + "', 'bad greek');");
        evaluateScript("window.assert(data.hebrew === '" + hebrew + "', 'bad hebrew');");
        evaluateScript("window.assert(data.arabic === '" + arabic + "', 'bad arabic');");
        evaluateScript("window.assert(data.hiragana === '" + hiragana + "', 'bad hiragana');");
        evaluateScript("window.assert(data.chinese === '" + chinese + "', 'bad chinese');");

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return
    }

    public static class Latch extends ScriptableObject
    {
        private volatile CountDownLatch latch;

        public String getClassName()
        {
            return "Latch";
        }

        public void jsConstructor(int count)
        {
            latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }

        public void jsFunction_countDown()
        {
            latch.countDown();
        }
    }
}
