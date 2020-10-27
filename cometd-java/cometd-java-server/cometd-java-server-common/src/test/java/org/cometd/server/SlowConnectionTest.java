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
package org.cometd.server;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.http.JSONTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.io.EofException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SlowConnectionTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testSessionSweptDoesNotSendReconnectNoneAdvice(String serverTransport) throws Exception {
        long maxInterval = 1000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(serverTransport, options);

        CountDownLatch sweeperLatch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout) {
                if (timeout) {
                    sweeperLatch.countDown();
                }
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        // Do not send the second connect, so the sweeper can do its job
        Assertions.assertTrue(sweeperLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS));

        // Send the second connect, we should not get the reconnect:"none" advice
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.send();
        Assertions.assertEquals(200, response.getStatus());

        Message.Mutable reply = new JettyJSONContextClient().parse(response.getContentAsString())[0];
        Assertions.assertEquals(Channel.META_CONNECT, reply.getChannel());
        Map<String, Object> advice = reply.getAdvice(false);
        if (advice != null) {
            Assertions.assertNotEquals(advice.get(Message.RECONNECT_FIELD), Message.RECONNECT_NONE_VALUE);
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSessionSweptWhileWritingQueueDoesNotSendReconnectNoneAdvice(String serverTransport) throws Exception {
        long maxInterval = 1000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(serverTransport, options);

        String channelName = "/test";
        JSONTransport transport = new JSONTransport(bayeux) {
            @Override
            protected void writeMessage(HttpServletResponse response, ServletOutputStream output, ServerSessionImpl session, ServerMessage message) throws IOException {
                try {
                    if (channelName.equals(message.getChannel())) {
                        session.scheduleExpiration(0, maxInterval);
                        TimeUnit.MILLISECONDS.sleep(2 * maxInterval);
                    }
                    super.writeMessage(response, output, session, message);
                } catch (InterruptedException x) {
                    throw new InterruptedIOException();
                }
            }
        };
        transport.init();
        bayeux.setTransports(transport);

        CountDownLatch sweeperLatch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.deliver(null, channelName, "test", Promise.noop());
            }

            @Override
            public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout) {
                if (timeout) {
                    sweeperLatch.countDown();
                }
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertTrue(sweeperLatch.await(maxInterval, TimeUnit.MILLISECONDS));

        Message.Mutable[] replies = new JettyJSONContextClient().parse(response.getContentAsString());
        Message.Mutable reply = replies[replies.length - 1];
        Assertions.assertEquals(Channel.META_CONNECT, reply.getChannel());
        Map<String, Object> advice = reply.getAdvice(false);
        if (advice != null) {
            Assertions.assertNotEquals(advice.get(Message.RECONNECT_FIELD), Message.RECONNECT_NONE_VALUE);
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSlowConnection(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        CountDownLatch sendLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        JSONTransport transport = new JSONTransport(bayeux) {
            @Override
            protected void writeMessage(HttpServletResponse response, ServletOutputStream output, ServerSessionImpl session, ServerMessage message) throws IOException {
                if (!message.isMeta() && !message.isPublishReply()) {
                    sendLatch.countDown();
                    await(closeLatch);
                    // Simulate that an exception is being thrown while writing
                    throw new EofException("test_exception");
                }
                super.writeMessage(response, output, session, message);
            }
        };
        transport.init();
        bayeux.setTransports(transport);
        long maxInterval = 5000L;
        transport.setMaxInterval(maxInterval);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);
        String cookieName = "BAYEUX_BROWSER";
        String browserId = extractCookie(cookieName);

        String channelName = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        // Send a server-side message so it gets written to the client
        bayeux.getChannel(channelName).publish(null, "x", Promise.noop());

        Socket socket = new Socket("localhost", port);
        OutputStream output = socket.getOutputStream();
        byte[] content = ("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]").getBytes(StandardCharsets.UTF_8);
        String request = "" +
                "POST " + new URI(cometdURL).getPath() + "/connect HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Type: application/json;charset=UTF-8\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "Cookie: " + cookieName + "=" + browserId + "\r\n" +
                "\r\n";
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.write(content);
        output.flush();

        CountDownLatch removeLatch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(clientId);
        session.addListener((ServerSession.RemovedListener)(s, m, t) -> removeLatch.countDown());

        // Wait for messages to be written, but close the connection instead
        Assertions.assertTrue(sendLatch.await(5, TimeUnit.SECONDS));
        socket.close();
        closeLatch.countDown();

        // The session must be swept even if the server could not write a response
        // to the connect because of the exception.
        Assertions.assertTrue(removeLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testLargeMessageOnSlowConnection(String serverTransport) throws Exception {
        Map<String, String> options = new HashMap<>();
        long maxInterval = 5000;
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(serverTransport, options);
        connector.setIdleTimeout(1000);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);
        String cookieName = "BAYEUX_BROWSER";
        String browserId = extractCookie(cookieName);

        String channelName = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        // Send a server-side message so it gets written to the client
        char[] chars = new char[64 * 1024 * 1024];
        Arrays.fill(chars, 'z');
        String data = new String(chars);
        bayeux.getChannel(channelName).publish(null, data, Promise.noop());

        Socket socket = new Socket("localhost", port);
        OutputStream output = socket.getOutputStream();
        byte[] content = ("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]").getBytes(StandardCharsets.UTF_8);
        String request = "" +
                "POST " + new URI(cometdURL).getPath() + "/connect HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Type: application/json;charset=UTF-8\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "Cookie: " + cookieName + "=" + browserId + "\r\n" +
                "\r\n";
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.write(content);
        output.flush();

        CountDownLatch removeLatch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(clientId);
        session.addListener((ServerSession.RemovedListener)(s, m, t) -> removeLatch.countDown());

        // Do not read, the server should idle timeout and close the connection.

        // The session must be swept even if the server could not write a response
        // to the connect because of the exception.
        Assertions.assertTrue(removeLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS));
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        }
    }
}
