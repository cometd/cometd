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
package org.cometd.server.websocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SlowConnectionTest extends ClientServerWebSocketTest {
    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testLargeMessageOnSlowConnection(String wsType) throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        long timeout = 2000;
        serverOptions.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        long idleTimeout = 3000;
        serverOptions.put(AbstractWebSocketTransport.IDLE_TIMEOUT_OPTION, String.valueOf(idleTimeout));
        long maxInterval = 4000;
        serverOptions.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        prepareServer(wsType, serverOptions);
        startServer();

        bayeux.setDetailedDump(true);

        try (Socket socket = new Socket("localhost", connector.getLocalPort())) {
            OutputStream output = socket.getOutputStream();
            String upgrade = "" +
                    "GET " + cometdServletPath + " HTTP/1.1\r\n" +
                    "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n";
            output.write(upgrade.getBytes(StandardCharsets.UTF_8));
            output.flush();

            Utf8StringBuilder builder = new Utf8StringBuilder();
            InputStream input = socket.getInputStream();
            int crlfs = 0;
            while (true) {
                int read = input.read();
                if (read < 0) {
                    Assertions.fail("unexpected eof");
                }
                if (read == '\r' || read == '\n') {
                    ++crlfs;
                } else {
                    crlfs = 0;
                }
                builder.append((byte)read);
                if (crlfs == 4) {
                    break;
                }
            }
            Assertions.assertTrue(builder.toString().contains(" 101 "));

            JSONContext.Client jsonContext = new JettyJSONContextClient();
            String handshake = "[{" +
                    "\"id\":\"1\"," +
                    "\"channel\":\"/meta/handshake\"," +
                    "\"version\":\"1.0\"," +
                    "\"supportedConnectionTypes\":[\"websocket\"]" +
                    "}]";
            wsWrite(output, handshake);
            String text = wsRead(input);
            Message.Mutable handshakeReply = jsonContext.parse(text)[0];
            Assertions.assertEquals(Channel.META_HANDSHAKE, handshakeReply.getChannel());
            Assertions.assertTrue(handshakeReply.isSuccessful());
            String clientId = handshakeReply.getClientId();

            String channelName = "/slow";
            String subscribe = "[{" +
                    "\"id\":\"2\"," +
                    "\"channel\":\"/meta/subscribe\"," +
                    "\"clientId\":\"" + clientId + "\"," +
                    "\"subscription\": \"" + channelName + "\"" +
                    "}]";
            wsWrite(output, subscribe);
            text = wsRead(input);
            Message.Mutable subscribeReply = jsonContext.parse(text)[0];
            Assertions.assertEquals(Channel.META_SUBSCRIBE, subscribeReply.getChannel());
            Assertions.assertTrue(subscribeReply.isSuccessful());

            String connect1 = "[{" +
                    "\"id\":\"3\"," +
                    "\"channel\":\"/meta/connect\"," +
                    "\"connectionType\":\"websocket\"," +
                    "\"clientId\":\"" + clientId + "\"," +
                    "\"advice\": {\"timeout\":0}" +
                    "}]";
            wsWrite(output, connect1);
            text = wsRead(input);
            Message.Mutable connect1Reply = jsonContext.parse(text)[0];
            Assertions.assertEquals(Channel.META_CONNECT, connect1Reply.getChannel());
            Assertions.assertTrue(connect1Reply.isSuccessful());

            CountDownLatch removeLatch = new CountDownLatch(1);
            ServerSessionImpl session = (ServerSessionImpl)bayeux.getSession(clientId);
            session.addListener((ServerSession.RemovedListener)(s, m, t) -> removeLatch.countDown());

            String connect2 = "[{" +
                    "\"id\":\"4\"," +
                    "\"channel\":\"/meta/connect\"," +
                    "\"connectionType\":\"websocket\"," +
                    "\"clientId\":\"" + clientId + "\"" +
                    "}]";
            wsWrite(output, connect2);

            // Send a large server-side message that causes TCP congestion.
            char[] chars = new char[64 * 1024 * 1024];
            Arrays.fill(chars, 'x');
            String data = new String(chars);
            bayeux.getChannel(channelName).publish(null, data, Promise.noop());

            // After timeout ms, the /meta/connect reply should be added to the
            // queue to be written, but the connection is still TCP congested.
            // After idleTimeout ms, the connection should idle timeout, and
            // the session rescheduled for expiration.
            // After maxInterval, the session should be swept.

            Assertions.assertTrue(removeLatch.await(timeout + idleTimeout + 2 * maxInterval, TimeUnit.MILLISECONDS));
        }
    }

    private static void wsWrite(OutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        // FIN FLAG + TYPE=TEXT
        output.write(0x81);
        // MASK FLAG + LENGTH
        if (length < 126) {
            output.write(0x80 + length);
        } else if (length < 65536) {
            output.write(0x80 + 126);
            output.write((length >> 8) & 0xFF);
            output.write(length & 0xFF);
        } else {
            output.write(0x80 + 127);
            output.write((length >> 24) & 0xFF);
            output.write((length >> 16) & 0xFF);
            output.write((length >> 8) & 0xFF);
            output.write(length & 0xFF);
        }
        // MASK BYTES
        output.write(new byte[]{0, 0, 0, 0});
        // PAYLOAD
        output.write(bytes);
        output.flush();
    }

    private static String wsRead(InputStream input) throws IOException {
        int read = input.read();
        // FIN FLAG + TYPE=TEXT
        Assertions.assertEquals(0x81, read);
        read = input.read();
        int length;
        if (read < 126) {
            length = read;
        } else if (read < 127) {
            int hi = input.read();
            int lo = input.read();
            length = (hi << 8) + lo;
        } else {
            int b1 = input.read();
            int b2 = input.read();
            int b3 = input.read();
            int b4 = input.read();
            length = (b1 << 24) + (b2 << 16) + (b3 << 8) + b4;
        }
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; ++i) {
            bytes[i] = (byte)(input.read() & 0xFF);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
