/*
 * Copyright (c) 2008 the original author or authors.
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

package org.cometd.client.http;

import java.net.InetSocketAddress;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP2ClientResetTest extends HTTP2ClientServerTest {
    @Test
    public void testClientResetAfterMetaHandshakeSessionIsSwept() throws Exception {
        Map<String, String> options = new HashMap<>();
        long sweepPeriod = 500;
        options.put(BayeuxServerImpl.SWEEP_PERIOD_OPTION, String.valueOf(sweepPeriod));
        long maxInterval = 2000;
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        start(options);
        bayeux.setDetailedDump(true);

        Promise.Completable<Session> sessionPromise = new Promise.Completable<>();
        http2Client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, sessionPromise);
        Session session = sessionPromise.get(5, TimeUnit.SECONDS);

        CountDownLatch removedLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from(cometdURL), HttpVersion.HTTP_2, HttpFields.EMPTY);
        Promise.Completable<Stream> streamPromise = new Promise.Completable<>();
        session.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener() {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame) {
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream) {
                try {
                    Stream.Data data = stream.readData();
                    String json = UTF_8.decode(data.frame().getByteBuffer()).toString();
                    Message reply = new JettyJSONContextClient().parse(json).get(0);
                    assertEquals(true, reply.get(Message.SUCCESSFUL_FIELD));
                    String sessionId = (String)reply.get(Message.CLIENT_ID_FIELD);
                    ServerSession cometdSession = bayeux.getSession(sessionId);
                    cometdSession.addListener((ServerSession.RemovedListener)(s, m, t) -> removedLatch.countDown());

                    // Send a reset to the server.
                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                } catch (ParseException x) {
                    throw new RuntimeException(x);
                }
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        String metaHandshake = """
                [{
                  "id": "1",
                  "channel": "/meta/handshake",
                  "supportedConnectionTypes": ["long-polling"]
                }]
                """;
        stream.data(new DataFrame(stream.getId(), UTF_8.encode(metaHandshake), true), Callback.NOOP);

        // Do not send the /meta/connect

        // Wait for the server-side session to expire.
        assertTrue(removedLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS), bayeux.dump());
    }

    @Test
    public void testClientResetAfterFirstMetaConnectSessionIsSwept() throws Exception {
        Map<String, String> options = new HashMap<>();
        long sweepPeriod = 500;
        options.put(BayeuxServerImpl.SWEEP_PERIOD_OPTION, String.valueOf(sweepPeriod));
        long maxInterval = 2000;
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        start(options);
        bayeux.setDetailedDump(true);

        Promise.Completable<Session> sessionPromise = new Promise.Completable<>();
        http2Client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, sessionPromise);
        Session session = sessionPromise.get(5, TimeUnit.SECONDS);

        AtomicReference<HttpFields> handshakeResponseRef = new AtomicReference<>();
        AtomicReference<Message> handshakeReplyRef = new AtomicReference<>();
        CountDownLatch handshakeLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from(cometdURL), HttpVersion.HTTP_2, HttpFields.EMPTY);
        Promise.Completable<Stream> streamPromise = new Promise.Completable<>();
        session.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener() {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame) {
                handshakeResponseRef.set(frame.getMetaData().getHttpFields());
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream) {
                try {
                    Stream.Data data = stream.readData();
                    String json = UTF_8.decode(data.frame().getByteBuffer()).toString();
                    Message reply = new JettyJSONContextClient().parse(json).get(0);
                    handshakeReplyRef.set(reply);
                    handshakeLatch.countDown();
                } catch (ParseException x) {
                    throw new RuntimeException(x);
                }
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        String metaHandshake = """
                [{
                  "id": "1",
                  "channel": "/meta/handshake",
                  "supportedConnectionTypes": ["long-polling"]
                }]
                """;
        stream.data(new DataFrame(stream.getId(), UTF_8.encode(metaHandshake), true), Callback.NOOP);

        assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Message handshakeReply = handshakeReplyRef.get();
        assertEquals(true, handshakeReply.get(Message.SUCCESSFUL_FIELD));
        String cookie = handshakeResponseRef.get().get(HttpHeader.SET_COOKIE);
        cookie = cookie.substring(0, cookie.indexOf(";"));
        String sessionId = (String)handshakeReply.get(Message.CLIENT_ID_FIELD);

        CountDownLatch removedLatch = new CountDownLatch(1);
        HttpFields headers = HttpFields.build().put(HttpHeader.COOKIE, cookie);
        request = new MetaData.Request("POST", HttpURI.from(cometdURL), HttpVersion.HTTP_2, headers);
        streamPromise = new Promise.Completable<>();
        session.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener() {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame) {
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream) {
                try {
                    Stream.Data data = stream.readData();
                    String json = UTF_8.decode(data.frame().getByteBuffer()).toString();
                    Message reply = new JettyJSONContextClient().parse(json).get(0);
                    assertEquals(true, reply.get(Message.SUCCESSFUL_FIELD));
                    ServerSession cometdSession = bayeux.getSession(sessionId);
                    cometdSession.addListener((ServerSession.RemovedListener)(s, m, t) -> removedLatch.countDown());

                    // Send a reset to the server.
                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                } catch (ParseException x) {
                    throw new RuntimeException(x);
                }
            }
        });
        stream = streamPromise.get(5, TimeUnit.SECONDS);

        String metaConnect = """
                [{
                  "id": "2",
                  "channel": "/meta/connect",
                  "connectionType": "long-polling",
                  "clientId": "%s",
                  "advice": { "timeout": 0 }
                }]
                """.formatted(sessionId);
        stream.data(new DataFrame(stream.getId(), UTF_8.encode(metaConnect), true), Callback.NOOP);

        // Wait for the server-side session to expire.
        assertTrue(removedLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS), bayeux.dump());
    }

    @Test
    public void testClientResetAfterSuspendedMetaConnectSessionIsSwept() throws Exception {
        Map<String, String> options = new HashMap<>();
        long sweepPeriod = 500;
        options.put(BayeuxServerImpl.SWEEP_PERIOD_OPTION, String.valueOf(sweepPeriod));
        long maxInterval = 2000;
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        long timeout = 3000;
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        start(options);
        bayeux.setDetailedDump(true);

        Promise.Completable<Session> sessionPromise = new Promise.Completable<>();
        http2Client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, sessionPromise);
        Session session = sessionPromise.get(5, TimeUnit.SECONDS);

        AtomicReference<HttpFields> metaHandshakeResponseRef = new AtomicReference<>();
        AtomicReference<Message> metaHandshakeReplyRef = new AtomicReference<>();
        CountDownLatch metaHandshakeLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from(cometdURL), HttpVersion.HTTP_2, HttpFields.EMPTY);
        Promise.Completable<Stream> streamPromise = new Promise.Completable<>();
        session.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener() {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame) {
                metaHandshakeResponseRef.set(frame.getMetaData().getHttpFields());
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream) {
                try {
                    Stream.Data data = stream.readData();
                    String json = UTF_8.decode(data.frame().getByteBuffer()).toString();
                    Message reply = new JettyJSONContextClient().parse(json).get(0);
                    metaHandshakeReplyRef.set(reply);
                    metaHandshakeLatch.countDown();
                } catch (ParseException x) {
                    throw new RuntimeException(x);
                }
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        String metaHandshake = """
                [{
                  "id": "1",
                  "channel": "/meta/handshake",
                  "supportedConnectionTypes": ["long-polling"]
                }]
                """;
        stream.data(new DataFrame(stream.getId(), UTF_8.encode(metaHandshake), true), Callback.NOOP);

        assertTrue(metaHandshakeLatch.await(5, TimeUnit.SECONDS));
        Message metaHandshakeReply = metaHandshakeReplyRef.get();
        assertEquals(true, metaHandshakeReply.get(Message.SUCCESSFUL_FIELD));
        String cookie = metaHandshakeResponseRef.get().get(HttpHeader.SET_COOKIE);
        cookie = cookie.substring(0, cookie.indexOf(";"));
        String sessionId = (String)metaHandshakeReply.get(Message.CLIENT_ID_FIELD);

        AtomicReference<Message> metaConnectReplyRef = new AtomicReference<>();
        CountDownLatch metaConnectLatch1 = new CountDownLatch(1);
        HttpFields headers = HttpFields.build().put(HttpHeader.COOKIE, cookie);
        request = new MetaData.Request("POST", HttpURI.from(cometdURL), HttpVersion.HTTP_2, headers);
        streamPromise = new Promise.Completable<>();
        session.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener() {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame) {
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream) {
                try {
                    Stream.Data data = stream.readData();
                    String json = UTF_8.decode(data.frame().getByteBuffer()).toString();
                    Message reply = new JettyJSONContextClient().parse(json).get(0);
                    metaConnectReplyRef.set(reply);
                    metaConnectLatch1.countDown();
                } catch (ParseException x) {
                    throw new RuntimeException(x);
                }
            }
        });
        stream = streamPromise.get(5, TimeUnit.SECONDS);

        String metaConnect1 = """
                [{
                  "id": "2",
                  "channel": "/meta/connect",
                  "connectionType": "long-polling",
                  "clientId": "%s",
                  "advice": { "timeout": 0 }
                }]
                """.formatted(sessionId);
        stream.data(new DataFrame(stream.getId(), UTF_8.encode(metaConnect1), true), Callback.NOOP);

        assertTrue(metaConnectLatch1.await(5, TimeUnit.SECONDS));
        Message metaConnectReply = metaConnectReplyRef.get();
        assertEquals(true, metaConnectReply.get(Message.SUCCESSFUL_FIELD));

        CountDownLatch metaConnectLatch2 = new CountDownLatch(1);
        streamPromise = new Promise.Completable<>();
        session.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener() {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame) {
                metaConnectLatch2.countDown();
            }
        });
        stream = streamPromise.get(5, TimeUnit.SECONDS);

        String metaConnect2 = """
                [{
                  "id": "2",
                  "channel": "/meta/connect",
                  "connectionType": "long-polling",
                  "clientId": "%s"
                }]
                """.formatted(sessionId);
        stream.data(new DataFrame(stream.getId(), UTF_8.encode(metaConnect2), true), Callback.NOOP);

        // The /meta/connect must have been suspended, wait half of the timeout to be sure.
        assertFalse(metaConnectLatch2.await(timeout / 2, TimeUnit.MILLISECONDS));

        CountDownLatch removedLatch = new CountDownLatch(1);
        ServerSession cometdSession = bayeux.getSession(sessionId);
        cometdSession.addListener((ServerSession.RemovedListener)(s, m, t) -> removedLatch.countDown());

        // Send a reset to the server.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);

        // Wait for the server-side session to expire.
        assertTrue(removedLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS), bayeux.dump());
    }
}
