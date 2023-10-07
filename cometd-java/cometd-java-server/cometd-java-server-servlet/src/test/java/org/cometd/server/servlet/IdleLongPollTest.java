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
package org.cometd.server.servlet;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.AsyncEvent;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.servlet.transport.ServletHttpScheduler;
import org.cometd.server.servlet.transport.ServletJSONTransport;
import org.cometd.server.transport.TransportContext;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class IdleLongPollTest extends AbstractBayeuxClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleLongPollDoesNotCauseMultipleClientsAdvice(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        long timeout = 2000;
        long sleep = 500;
        ServletJSONTransport transport = new ServletJSONTransport(bayeux) {
            @Override
            protected HttpScheduler newHttpScheduler(TransportContext context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
                return new ServletHttpScheduler(this, context, message, timeout) {
                    private final AtomicInteger decrements = new AtomicInteger();

                    @Override
                    public void onComplete(AsyncEvent asyncEvent) {
                        if (decrements.incrementAndGet() == 1) {
                            // Simulate that onComplete() is delayed without blocking
                            // this thread, to cause a race condition
                            new Thread(() -> {
                                try {
                                    Thread.sleep(sleep);
                                    superOnComplete(asyncEvent);
                                } catch (Exception x) {
                                    x.printStackTrace();
                                }
                            }).start();
                        } else {
                            superOnComplete(asyncEvent);
                        }
                    }

                    private void superOnComplete(AsyncEvent asyncEvent) {
                        super.onComplete(asyncEvent);
                    }
                };
            }
        };
        transport.setOption(AbstractServerTransport.TIMEOUT_OPTION, timeout);
        transport.init();
        bayeux.setTransports(transport);

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

        ServerSession serverSession = bayeux.getSession(clientId);
        Assertions.assertNotNull(serverSession);

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.send();
        Assertions.assertEquals(200, response.getStatus());

        Request connect3 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect3.send();
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.length);
        Message.Mutable message = messages[0];
        Map<String, Object> advice = message.getAdvice();
        Assertions.assertNull(advice);
    }
}
