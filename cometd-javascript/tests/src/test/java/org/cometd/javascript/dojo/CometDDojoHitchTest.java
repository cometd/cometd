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
package org.cometd.javascript.dojo;

import org.cometd.javascript.Latch;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDDojoHitchTest extends AbstractCometDDojoTest {
    @Test
    public void testDojoHitch() throws Exception {
        evaluateScript("const handshakeLatch = new Latch(1);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/handshake', () => handshakeLatch.countDown());
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(handshakeLatch.await(5000));

        evaluateScript("const latch1 = new Latch(1);");
        Latch latch1 = javaScript.get("latch1");
        evaluateScript("const latch2 = new Latch(1);");
        Latch latch2 = javaScript.get("latch2");
        evaluateScript("""
                // dojo.hitch() calls apply(scope, arguments), but GraalJS throws because
                // the arity is wrong, so explicitly invoke call() with zero arguments.
                // See also issue #742.
                cometd.subscribe('/test', () => dojo.hitch(latch1, 'countDown').call(latch1) , {});
                cometd.subscribe('/test', () => dojo.hitch(latch2, 'countDown').call(latch2));
                cometd.publish('/test', {});
                """);
        Assertions.assertTrue(latch1.await(5000));
        Assertions.assertTrue(latch2.await(5000));

        disconnect();
    }
}
