/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDMaxMessageSizeTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testClientMaxSendBayeuxMessageSize(String transport) throws Exception {
        initCometDServer(transport);

        int maxMessageSize = 512;
        char[] chars = new char[maxMessageSize];
        Arrays.fill(chars, 'a');
        String data = new String(chars);

        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "maxSendBayeuxMessageSize: " + maxMessageSize + ", " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");

        String channelName = "/max_msg";

        CountDownLatch serverLatch = new CountDownLatch(1);
        bayeuxServer.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession sender, ServerChannel channel, ServerMessage.Mutable message) {
                serverLatch.countDown();
                return true;
            }
        });

        evaluateScript("var clientLatch = new Latch(1);");
        Latch clientLatch = javaScript.get("clientLatch");
        evaluateScript("cometd.handshake(function(hsReply) {" +
                "  if (hsReply.successful) {" +
                "    cometd.publish('" + channelName + "', '" + data + "', function(reply) {" +
                "      if (!reply.successful) {" +
                "        clientLatch.countDown();" +
                "      }" +
                "    });" +
                "  }" +
                "});");

        Assertions.assertTrue(clientLatch.await(5000));
        // The message should not reach the server.
        Assertions.assertFalse(serverLatch.await(1, TimeUnit.SECONDS));
    }
}
