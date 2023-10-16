/*
 * Copyright (c) 2008-2022 the original author or authors.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDCharsetTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testCharset(String transport) throws Exception {
        initCometDServer(transport);

        String cyrillic = "\u0436"; // zhe
        String greek = "\u0398"; // theta
        String hebrew = "\u05d0"; // aleph
        String arabic = "\u0644"; // lam
        String hiragana = "\u3068"; // to
        String chinese = "\u2ee2"; // cjk's horse

        evaluateScript("""
                cometd.init({url: '$U', logLevel: '$L'});
                const latch = new Latch(1);
                let data;
                cometd.subscribe('/echo', message => { data = message.data; latch.countDown(); });
                cometd.publish('/echo', {
                    cyrillic: '$CY',
                    greek: '$GR',
                    hebrew: '$HE',
                    arabic: '$AR',
                    hiragana: '$HI',
                    chinese: '$CH'
                });
                """.replace("$U", cometdURL).replace("$L", getLogLevel())
                .replace("$CY", cyrillic)
                .replace("$GR", greek)
                .replace("$HE", hebrew)
                .replace("$AR", arabic)
                .replace("$HI", hiragana)
                .replace("$CH", chinese));
        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));
        evaluateScript("""
                window.assert(data !== undefined, 'data is undefined');
                window.assert(data.cyrillic === '$CY', 'bad cyrillic');
                window.assert(data.greek === '$GR', 'bad greek');
                window.assert(data.hebrew === '$HE', 'bad hebrew');
                window.assert(data.arabic === '$AR', 'bad arabic');
                window.assert(data.hiragana === '$HI', 'bad hiragana');
                window.assert(data.chinese === '$CH', 'bad chinese');
                """.replace("$CY", cyrillic)
                .replace("$GR", greek)
                .replace("$HE", hebrew)
                .replace("$AR", arabic)
                .replace("$HI", hiragana)
                .replace("$CH", chinese));

        disconnect();
    }
}
