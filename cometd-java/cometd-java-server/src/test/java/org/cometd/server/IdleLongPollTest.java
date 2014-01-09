/*
 * Copyright (c) 2008-2014 the original author or authors.
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class IdleLongPollTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testIdleLongPollDoesNotCauseMultipleClientsAdvice() throws Exception
    {
        startServer(null);

        final long timeout = 2000;
        final long sleep = 500;
        bayeux.setTransports(new JSONTransport(bayeux)
        {
            {
                setOption(TIMEOUT_OPTION, timeout);
                init();
            }

            @Override
            protected LongPollScheduler newLongPollScheduler(ServerSessionImpl session, AsyncContext asyncContext, ServerMessage.Mutable metaConnectReply, String browserId)
            {
                return new LongPollScheduler(session, asyncContext, metaConnectReply, browserId)
                {
                    private final AtomicInteger decrements = new AtomicInteger();

                    @Override
                    public void onComplete(final AsyncEvent asyncEvent) throws IOException
                    {
                        if (decrements.incrementAndGet() == 1)
                        {
                            // Simulate that onComplete() is delayed without blocking
                            // this thread, to cause a race condition
                            new Thread()
                            {
                                @Override
                                public void run()
                                {
                                    try
                                    {
                                        Thread.sleep(sleep);
                                        superOnComplete(asyncEvent);
                                    }
                                    catch (Exception x)
                                    {
                                        x.printStackTrace();
                                    }
                                }
                            }.start();
                        }
                        else
                        {
                            superOnComplete(asyncEvent);
                        }
                    }

                    private void superOnComplete(AsyncEvent asyncEvent) throws IOException
                    {
                        super.onComplete(asyncEvent);
                    }
                };
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

        ServerSession serverSession = bayeux.getSession(clientId);
        Assert.assertNotNull(serverSession);

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.send();
        Assert.assertEquals(200, response.getStatus());

        Request connect3 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect3.send();
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        Message.Mutable message = messages[0];
        Map<String,Object> advice = message.getAdvice();
        Assert.assertNull(advice);
    }
}
