/*
 * Copyright (c) 2011 the original author or authors.
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

package org.cometd.client;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.common.Jackson1JSONContextClient;
import org.cometd.common.Jackson2JSONContextClient;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.Jackson1JSONContextServer;
import org.cometd.server.Jackson2JSONContextServer;
import org.cometd.server.ServerMessageImpl;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class JacksonContextTest extends ClientServerTest
{
	@Parameters(name= "{index}: Jackson Context Server: {0} Jackson Context Client: {1}")
 	public static Iterable<Object[]> data() 
 	{
 		return Arrays.asList(new Object[][] 
 				{ 
 					{ Jackson2JSONContextServer.class, Jackson2JSONContextClient.class }, 
 					{ Jackson1JSONContextServer.class, Jackson1JSONContextClient.class },
 				}
 		);
     }

	private final String jacksonContextServerClassName;
	private final String jacksonContextClientClassName;

	public JacksonContextTest(final Object jacksonContextServerClass, final Object jacksonContextClientClass) 
	{
		this.jacksonContextServerClassName = ((Class<?>) jacksonContextServerClass).getName();
		this.jacksonContextClientClassName =  ((Class<?>) jacksonContextClientClass).getName();
	}
	
    @Test
    public void testAllMessagesUseJackson() throws Exception
    {
        Map<String, String> serverParams = new HashMap<>();
        serverParams.put(BayeuxServerImpl.JSON_CONTEXT, jacksonContextServerClassName);
        startServer(serverParams);

        Map<String, Object> clientParams = new HashMap<>();
        clientParams.put(ClientTransport.JSON_CONTEXT, jacksonContextClientClassName);
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(clientParams, httpClient));
        client.setDebugEnabled(debugTests());

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        final String channelName = "/test_jackson";
        final CountDownLatch localLatch = new CountDownLatch(2);
        new AbstractService(bayeux, channelName)
        {
            {
                addService(channelName, "process");

                getLocalSession().getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                {
                    private boolean republishSeen;

                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        System.err.println("local message = " + message);
                        Map<String, Object> data = message.getDataAsMap();
                        Assert.assertTrue(data.containsKey("publish"));
                        republishSeen |= data.containsKey("republish");
                        if (localLatch.getCount() == 1 && !republishSeen)
                            Assert.fail();
                        localLatch.countDown();
                    }
                });
            }

            public void process(ServerSession session, ServerMessage message)
            {
                // Republish
                Map<String, Object> data = message.getDataAsMap();
                Map<String, Object> republishData = new HashMap<>(data);
                republishData.put("republish", true);
                getBayeux().getChannel(channelName).publish(getServerSession(), republishData);
                // Deliver
                Map<String, Object> deliverData = new HashMap<>(data);
                deliverData.put("deliver", true);
                session.deliver(getServerSession(), channelName, deliverData, null);
            }
        };

        // Clear out the jsonContexts embedded in the message classes
        Field clientJSONContext = HashMapMessage.class.getDeclaredField("_jsonContext");
        clientJSONContext.setAccessible(true);
        clientJSONContext.set(null, null);
        Field serverJSONContext = ServerMessageImpl.class.getDeclaredField("_jsonContext");
        serverJSONContext.setAccessible(true);
        serverJSONContext.set(null, null);

        final ClientSessionChannel channel = client.getChannel(channelName);
        final CountDownLatch clientLatch = new CountDownLatch(3);
        client.batch(new Runnable()
        {
            public void run()
            {
                channel.subscribe(new ClientSessionChannel.MessageListener()
                {
                    private boolean republishSeen;
                    private boolean deliverSeen;

                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        System.err.println("message = " + message);
                        Map<String, Object> data = message.getDataAsMap();
                        Assert.assertTrue(data.containsKey("publish"));
                        republishSeen |= data.containsKey("republish");
                        deliverSeen |= data.containsKey("deliver");
                        if (clientLatch.getCount() == 1 && !republishSeen && !deliverSeen)
                            Assert.fail();
                        clientLatch.countDown();
                    }
                });
                Map<String, Object> data = new HashMap<>();
                data.put("publish", true);
                channel.publish(data);
            }
        });

        Assert.assertTrue(localLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
