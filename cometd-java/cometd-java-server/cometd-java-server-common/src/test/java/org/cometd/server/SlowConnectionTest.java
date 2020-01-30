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

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

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
import org.junit.Assert;
import org.junit.Test;

public class SlowConnectionTest extends AbstractBayeuxClientServerTest {
    public SlowConnectionTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testSessionSweptDoesNotSendReconnectNoneAdvice() throws Exception {
        long maxInterval = 1000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(options);

        final CountDownLatch sweeperLatch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
                if (timedout) {
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
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        // Do not send the second connect, so the sweeper can do its job
        Assert.assertTrue(sweeperLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS));

        // Send the second connect, we should not get the reconnect:"none" advice
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.send();
        Assert.assertEquals(200, response.getStatus());

        Message.Mutable reply = new JettyJSONContextClient().parse(response.getContentAsString())[0];
        Assert.assertEquals(Channel.META_CONNECT, reply.getChannel());
        Map<String, Object> advice = reply.getAdvice(false);
        if (advice != null) {
            Assert.assertFalse(Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD)));
        }
    }

    @Test
    public void testSessionSweptWhileWritingQueueDoesNotSendReconnectNoneAdvice() throws Exception {
        final long maxInterval = 1000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(options);

        final String channelName = "/test";
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

        final CountDownLatch sweeperLatch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.deliver(null, channelName, "test", Promise.noop());
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
                if (timedout) {
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
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(sweeperLatch.await(maxInterval, TimeUnit.MILLISECONDS));

        Message.Mutable[] replies = new JettyJSONContextClient().parse(response.getContentAsString());
        Message.Mutable reply = replies[replies.length - 1];
        Assert.assertEquals(Channel.META_CONNECT, reply.getChannel());
        Map<String, Object> advice = reply.getAdvice(false);
        if (advice != null) {
            Assert.assertFalse(Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD)));
        }
    }

    @Test
    public void testSlowConnection() throws Exception {
        startServer(null);

        final CountDownLatch sendLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final JSONTransport transport = new JSONTransport(bayeux) {
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
        Assert.assertEquals(200, response.getStatus());

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
        Assert.assertEquals(200, response.getStatus());

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

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

        final CountDownLatch removeLatch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(clientId);
        session.addListener((ServerSession.RemoveListener)(s, t) -> removeLatch.countDown());

        // Wait for messages to be written, but close the connection instead
        Assert.assertTrue(sendLatch.await(5, TimeUnit.SECONDS));
        socket.close();
        closeLatch.countDown();

        // The session must be swept even if the server could not write a response
        // to the connect because of the exception.
        Assert.assertTrue(removeLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testLargeMessageOnSlowConnection() throws Exception {
        Map<String, String> options = new HashMap<>();
        long maxInterval = 5000;
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(options);
        connector.setIdleTimeout(1000);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

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
        Assert.assertEquals(200, response.getStatus());

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

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

        final CountDownLatch removeLatch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(clientId);
        session.addListener((ServerSession.RemoveListener)(s, t) -> removeLatch.countDown());

        // Do not read, the server should idle timeout and close the connection.

        // The session must be swept even if the server could not write a response
        // to the connect because of the exception.
        Assert.assertTrue(removeLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS));
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        }
    }
}
