package org.cometd.server;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.http.HttpHeaders;
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
            protected LongPollScheduler newLongPollScheduler(ServerSessionImpl session, Continuation continuation, ServerMessage.Mutable metaConnectReply, final String browserId)
            {
                return new LongPollScheduler(session, continuation, metaConnectReply, browserId)
                {
                    private final AtomicInteger decrements = new AtomicInteger();

                    @Override
                    public void onComplete(final Continuation continuation)
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
                                        superOnComplete(continuation);
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
                            superOnComplete(continuation);
                        }
                    }

                    private void superOnComplete(Continuation continuation)
                    {
                        super.onComplete(continuation);
                    }
                };
            }
        });

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        Assert.assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        ContentExchange connect1 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect1.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect1);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, connect1.waitForDone());
        Assert.assertEquals(200, connect1.getResponseStatus());

        ServerSession serverSession = bayeux.getSession(clientId);
        Assert.assertNotNull(serverSession);

        ContentExchange connect2 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect2.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect2);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, connect2.waitForDone());
        Assert.assertEquals(200, connect2.getResponseStatus());

        ContentExchange connect3 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect3.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect3);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, connect3.waitForDone());
        Assert.assertEquals(200, connect3.getResponseStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(connect3.getResponseContent());
        Assert.assertEquals(1, messages.length);
        Message.Mutable message = messages[0];
        Map<String,Object> advice = message.getAdvice();
        Assert.assertNull(advice);
    }
}
