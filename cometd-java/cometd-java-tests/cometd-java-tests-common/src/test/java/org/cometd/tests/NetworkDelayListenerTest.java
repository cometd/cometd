/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.tests;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.common.JSONContext;
import org.cometd.server.JSONContextServer;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.ServerMessageImpl;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.eclipse.jetty.websocket.core.internal.WebSocketCore;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkDelayListenerTest extends AbstractClientServerTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkDelayListenerTest.class);

    public NetworkDelayListenerTest(Transport transport) {
        super(transport);
    }

    @Test
    public void testNetworkDelayListener() throws Exception {
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            cometdServletPath = "/cometd";
            int port = serverSocket.socket().getLocalPort();
            cometdURL = "http://localhost:" + port + cometdServletPath;
            LOGGER.debug("Listening on localhost:{}", port);
            startClient();

            long maxNetworkDelay = 1000;
            Map<String, Object> clientOptions = new HashMap<>();
            clientOptions.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
            BayeuxClient bayeuxClient = new BayeuxClient(cometdURL, newClientTransport(clientOptions));

            BlockingQueue<Message> metaConnectQueue = new LinkedBlockingDeque<>();
            AtomicLong lastMessageNanos = new AtomicLong();
            bayeuxClient.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(c, m) -> {
                metaConnectQueue.offer(m);
                lastMessageNanos.set(System.nanoTime());
            });

            CountDownLatch timeoutLatch = new CountDownLatch(1);
            bayeuxClient.addTransportListener(new TransportListener() {
                @Override
                public void onTimeout(List<? extends Message> messages, Promise<Long> promise) {
                    timeoutLatch.countDown();
                    long idleMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastMessageNanos.get());
                    promise.succeed(maxNetworkDelay - idleMillis);
                }
            });

            long timeout = 2000;
            long delay = timeout + 2 * maxNetworkDelay;
            long pause = 500;
            long count = delay / pause;

            String channelName = "/maxNetworkDelay";
            CountDownLatch messageLatch = new CountDownLatch((int)count);
            new Thread(() -> bayeuxClient.handshake(hsReply1 -> bayeuxClient.getChannel(channelName).addListener((ClientSessionChannel.MessageListener)(c, m) -> {
                LOGGER.info("message = {}", m);
                messageLatch.countDown();
            }))).start();

            SocketChannel socket = serverSocket.accept();

            JSONContextServer json = new JettyJSONContextServer();
            JSONContext.Generator jsonGenerator = json.getGenerator();

            ByteBuffer buffer = isWebSocket(transport) ? websocketUpgrade(socket) : BufferUtil.allocate(1024);

            // Read the /meta/handshake message.
            ServerMessage hs = receiveMessage(socket, buffer, json);

            // Write the /meta/handshake reply.
            ServerMessage.Mutable hsReply = new ServerMessageImpl();
            hsReply.setId(hs.getId());
            hsReply.setSuccessful(true);
            hsReply.setChannel(hs.getChannel());
            hsReply.setClientId("0123456789");
            String connectionType = bayeuxClient.getTransport().getName();
            hsReply.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, new String[]{connectionType});
            Map<String, Object> hsAdvice = hsReply.getAdvice(true);
            hsAdvice.put(Message.TIMEOUT_FIELD, 0L);
            hsAdvice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
            String hsReplyJSON = jsonGenerator.generate(new Object[]{hsReply});
            sendMessage(socket, hsReplyJSON);

            // Read the first /meta/connect.
            ServerMessage cn1 = receiveMessage(socket, buffer, json);

            // Write the first /meta/connect reply.
            ServerMessage.Mutable cn1Reply = new ServerMessageImpl();
            cn1Reply.setId(cn1.getId());
            cn1Reply.setSuccessful(true);
            cn1Reply.setChannel(cn1.getChannel());
            cn1Reply.put(Message.CONNECTION_TYPE_FIELD, connectionType);
            Map<String, Object> cn1Advice = cn1Reply.getAdvice(true);
            cn1Advice.put(Message.TIMEOUT_FIELD, timeout);
            String cn1ReplyJSON = jsonGenerator.generate(new Object[]{cn1Reply});
            sendMessage(socket, cn1ReplyJSON);

            Message metaConnect = metaConnectQueue.poll(5, TimeUnit.SECONDS);
            Assert.assertNotNull(metaConnect);
            Assert.assertTrue(metaConnect.isSuccessful());
            LOGGER.debug("[client] received {}", metaConnect);

            // Read the second /meta/connect.
            ServerMessage cn2 = receiveMessage(socket, buffer, json);

            // Delay the second /meta/connect reply,
            // but send some message to trigger the
            // extension, simulating slow message arrival.

            sendMessageChunkBegin(socket);

            // Write the messages.
            for (int i = 0; i < count; ++i) {
                Thread.sleep(pause);
                ServerMessage.Mutable message = new ServerMessageImpl();
                message.setChannel(channelName);
                message.setData("DATA_" + i);
                String messageJSON = jsonGenerator.generate(message);
                sendMessageChunk(socket, messageJSON, i == 0);
                LOGGER.debug("[server] sent {}", messageJSON);
                lastMessageNanos.set(System.nanoTime());
            }

            // Write the second /meta/connect reply.
            ServerMessage.Mutable cn2Reply = new ServerMessageImpl();
            cn2Reply.setId(cn2.getId());
            cn2Reply.setSuccessful(true);
            cn2Reply.setChannel(cn2.getChannel());
            cn2Reply.put(Message.CONNECTION_TYPE_FIELD, connectionType);
            String cn2ReplyJSON = jsonGenerator.generate(cn2Reply);
            sendMessageChunk(socket, cn2ReplyJSON, false);

            sendMessageChunkEnd(socket);
            LOGGER.debug("[server] sent {}", cn2ReplyJSON);

            // Check that the listener was called at least once.
            Assert.assertTrue(timeoutLatch.await(5, TimeUnit.SECONDS));

            // Check that the second /meta/connect was successful, even if it was delayed.
            metaConnect = metaConnectQueue.poll(5, TimeUnit.SECONDS);
            Assert.assertNotNull(metaConnect);
            Assert.assertTrue(metaConnect.isSuccessful());
            LOGGER.debug("[client] received {}", metaConnect);

            // Check that all messages arrived.
            Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

            // Read the third /meta/connect.
            ServerMessage cn3 = receiveMessage(socket, buffer, json);

            // Write the third /meta/connect reply.
            ServerMessage.Mutable cn3Reply = new ServerMessageImpl();
            cn3Reply.setId(cn3.getId());
            cn3Reply.setSuccessful(false);
            cn3Reply.setChannel(cn3.getChannel());
            cn3Reply.put(Message.CONNECTION_TYPE_FIELD, connectionType);
            Map<String, Object> cn3Advice = cn3Reply.getAdvice(true);
            cn3Advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
            String cn3ReplyJSON = jsonGenerator.generate(new Object[]{cn3Reply});
            sendMessage(socket, cn3ReplyJSON);

            // Check that the third /meta/connect arrived.
            metaConnect = metaConnectQueue.poll(5, TimeUnit.SECONDS);
            Assert.assertNotNull(metaConnect);
            Assert.assertFalse(metaConnect.isSuccessful());
            LOGGER.debug("[client] received {}", metaConnect);
        }
    }

    private ByteBuffer websocketUpgrade(SocketChannel socket) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        HttpTester.Request hsRequest = new HttpTester.Request();
        HttpParser parser = new HttpParser(hsRequest);
        while (!hsRequest.isComplete()) {
            buffer.clear();
            socket.read(buffer);
            buffer.flip();
            parser.parseNext(buffer);
        }

        String key = WebSocketCore.hashKey(hsRequest.get(HttpHeader.SEC_WEBSOCKET_KEY));
        String response = "" +
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + key + "\r\n" +
                "\r\n";
        socket.write(BufferUtil.toBuffer(response, StandardCharsets.UTF_8));
        return buffer;
    }

    private ServerMessage receiveMessage(SocketChannel socket, ByteBuffer buffer, JSONContextServer json) throws Exception {
        if (isWebSocket(transport)) {
            return receiveWebSocketMessage(socket, buffer, json);
        } else {
            return receiveHttpMessage(socket, buffer, json);
        }
    }

    private ServerMessage receiveWebSocketMessage(SocketChannel socket, ByteBuffer buffer, JSONContextServer json) throws Exception {
        Parser parser = new Parser(new ArrayByteBufferPool());
        Parser.ParsedFrame frame;
        while (true) {
            frame = parser.parse(buffer);
            if (frame != null)
                break;
            buffer.clear();
            socket.read(buffer);
            buffer.flip();
        }
        return json.parse(BufferUtil.toString(frame.getPayload(), StandardCharsets.UTF_8))[0];
    }

    private ServerMessage receiveHttpMessage(SocketChannel socket, ByteBuffer buffer, JSONContextServer json) throws Exception {
        HttpTester.Request request = new HttpTester.Request();
        HttpParser parser = new HttpParser(request);
        while (true) {
            if (parser.parseNext(buffer))
                break;
            buffer.clear();
            socket.read(buffer);
            buffer.flip();
        }
        return json.parse(request.getContent())[0];
    }

    private void sendMessage(SocketChannel socket, String content) throws IOException {
        if (isWebSocket(transport)) {
            sendWebSocketMessage(socket, content);
        } else {
            sendHttpMessage(socket, content);
        }
    }

    private void sendWebSocketMessage(SocketChannel socket, String content) throws IOException {
        ByteBuffer buffer = BufferUtil.toBuffer(content, StandardCharsets.UTF_8);
        Generator wsGenerator = new Generator();
        Frame frame = new Frame(OpCode.TEXT, buffer);
        ByteBuffer headerBuffer = BufferUtil.allocate(1024);
        wsGenerator.generateHeader(frame, headerBuffer);
        socket.write(new ByteBuffer[]{headerBuffer, buffer});
    }

    private void sendHttpMessage(SocketChannel socket, String content) throws IOException {
        ByteBuffer buffer = BufferUtil.toBuffer(content, StandardCharsets.UTF_8);
        ByteBuffer response = BufferUtil.toBuffer("HTTP/1.1 200 OK\r\n" +
                "ContentType: application/json\r\n" +
                "Content-Length: " + buffer.remaining() + "\r\n" +
                "\r\n", StandardCharsets.UTF_8);
        socket.write(new ByteBuffer[]{response, buffer});
    }

    private void sendMessageChunkBegin(SocketChannel socket) throws IOException {
        if (!isWebSocket(transport)) {
            ByteBuffer response = BufferUtil.toBuffer("HTTP/1.1 200 OK\r\n" +
                    "ContentType: application/json\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "1\r\n[\r\n", StandardCharsets.UTF_8);
            socket.write(new ByteBuffer[]{response});
        }
    }

    private void sendMessageChunk(SocketChannel socket, String content, boolean first) throws IOException {
        if (isWebSocket(transport)) {
            sendWebSocketMessage(socket, "[" + content + "]");
        } else {
            ByteBuffer comma = first ? BufferUtil.EMPTY_BUFFER : BufferUtil.toBuffer("1\r\n,\r\n", StandardCharsets.UTF_8);
            ByteBuffer buffer = BufferUtil.toBuffer(content, StandardCharsets.UTF_8);
            ByteBuffer begin = BufferUtil.toBuffer(Integer.toHexString(buffer.remaining()) + "\r\n");
            ByteBuffer end = BufferUtil.toBuffer("\r\n", StandardCharsets.UTF_8);
            socket.write(new ByteBuffer[]{comma, begin, buffer, end});
        }
    }

    private void sendMessageChunkEnd(SocketChannel socket) throws IOException {
        if (!isWebSocket(transport)) {
            ByteBuffer chunkEnd = BufferUtil.toBuffer("1\r\n]\r\n0\r\n\r\n", StandardCharsets.UTF_8);
            socket.write(new ByteBuffer[]{chunkEnd});
        }
    }

    private static boolean isWebSocket(Transport transport) {
        return transport == Transport.JAVAX_WEBSOCKET || transport == Transport.JETTY_WEBSOCKET || transport == Transport.OKHTTP_WEBSOCKET;
    }

    // TODO: remove this class once jetty-http-tools is deployed to Central.
    private static class HttpTester {
        private static class Request implements HttpParser.RequestHandler {
            private final HttpFields.Mutable headers = HttpFields.build();
            private final Utf8StringBuilder content = new Utf8StringBuilder();
            private boolean complete;

            @Override
            public void startRequest(String method, String uri, HttpVersion version) {
            }

            @Override
            public void parsedHeader(HttpField field) {
                headers.put(field);
            }

            @Override
            public boolean headerComplete() {
                return false;
            }

            @Override
            public boolean content(ByteBuffer buffer) {
                content.append(buffer);
                return false;
            }

            @Override
            public boolean contentComplete() {
                return false;
            }

            @Override
            public boolean messageComplete() {
                complete = true;
                return true;
            }

            @Override
            public void earlyEOF() {
            }

            public String get(HttpHeader header) {
                return headers.get(header);
            }

            public boolean isComplete() {
                return complete;
            }

            public String getContent() {
                return content.toString();
            }
        }
    }
}
