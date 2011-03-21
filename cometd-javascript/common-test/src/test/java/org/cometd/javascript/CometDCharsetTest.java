package org.cometd.javascript;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class CometDCharsetTest extends AbstractCometDTest
{
    @Test
    public void testCharset() throws Exception
    {
        String cyrillic = "\u0436"; // zhe
        String greek = "\u0398"; // theta
        String hebrew = "\u05d0"; // aleph
        String arabic = "\u0644"; // lam
        String hiragana = "\u3068"; // to
        String chinese = "\u2ee2"; // cjk's horse

        defineClass(Latch.class);
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateScript("var data = undefined;");
        evaluateScript("cometd.subscribe('/echo', function(message) { data = message.data; latch.countDown(); });");
        evaluateScript("cometd.publish('/echo', {" +
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

        evaluateScript("cometd.disconnect(true);");
    }
}
