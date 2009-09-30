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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdInitDisconnectTest extends AbstractCometdJQueryTest
{
    public void testCometInitDisconnect() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        defineClass(Listener.class);
        evaluateScript("var listener = new Listener();");
        Listener listener = get("listener");
        String script = "$.cometd.addListener('/**', listener, listener.handle);" +
                        // Expect 2 messages: handshake and connect
                        "listener.expect(2); $.cometd.handshake();";
        evaluateScript(script);
        assert listener.await(1000);

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        String status = evaluateScript("$.cometd.getStatus();");
        assert "connected".equals(status) : status;

        evaluateScript("listener.expect(1); $.cometd.disconnect();");
        assert listener.await(1000);

        // Wait for the disconnect to happen
        Thread.sleep(1000);

        status = evaluateScript("$.cometd.getStatus();");
        assert "disconnected".equals(status) : status;

        // Make sure there are no attempts to reconnect
        evaluateScript("listener.expect(1);");
        assert !listener.await(longPollingPeriod * 2);
    }

    public static class Listener extends ScriptableObject
    {
        private CountDownLatch latch;

        public void jsFunction_expect(int messageCount)
        {
            latch = new CountDownLatch(messageCount);
        }

        public String getClassName()
        {
            return "Listener";
        }

        public void jsFunction_handle(Object message)
        {
            if (latch.getCount() == 0) throw new AssertionError();
            latch.countDown();
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }
}
