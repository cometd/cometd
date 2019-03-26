/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CometDCharsetTest extends AbstractCometDTransportsTest {
    @Test
    public void testCharset() throws Exception {
        String cyrillic = "\u0436"; // zhe
        String greek = "\u0398"; // theta
        String hebrew = "\u05d0"; // aleph
        String arabic = "\u0644"; // lam
        String hiragana = "\u3068"; // to
        String chinese = "\u2ee2"; // cjk's horse

        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
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
        assertTrue(latch.await(5000));
        evaluateScript("window.assert(data !== undefined, 'data is undefined');");
        evaluateScript("window.assert(data.cyrillic === '" + cyrillic + "', 'bad cyrillic');");
        evaluateScript("window.assert(data.greek === '" + greek + "', 'bad greek');");
        evaluateScript("window.assert(data.hebrew === '" + hebrew + "', 'bad hebrew');");
        evaluateScript("window.assert(data.arabic === '" + arabic + "', 'bad arabic');");
        evaluateScript("window.assert(data.hiragana === '" + hiragana + "', 'bad hiragana');");
        evaluateScript("window.assert(data.chinese === '" + chinese + "', 'bad chinese');");

        disconnect();
    }
}
