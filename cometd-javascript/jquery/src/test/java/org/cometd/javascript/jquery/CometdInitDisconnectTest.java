// ========================================================================
// Copyright 2004-2008 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cometd.javascript.jquery;

import org.cometd.javascript.Latch;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdInitDisconnectTest extends AbstractCometdJQueryTest
{
    public void testInitDisconnect() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var latch = new Latch(2);");
        Latch latch = get("latch");
        String script = "$.cometd.addListener('/**', function(message) { window.console.info(message.channel); latch.countDown(); });" +
                        // Expect 2 messages: handshake and connect
                        "$.cometd.handshake();";
        evaluateScript(script);
        assertTrue(latch.await(1000));

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        String status = evaluateScript("$.cometd.getStatus();");
        assertEquals("connected", status);

        // Expect disconnect and connect
        latch.reset(2);
        evaluateScript("$.cometd.disconnect(true);");
        assertTrue(latch.await(1000));

        status = evaluateScript("$.cometd.getStatus();");
        assertEquals("disconnected", status);

        // Make sure there are no attempts to reconnect
        latch.reset(1);
        assertFalse(latch.await(longPollingPeriod * 3));
    }
}
