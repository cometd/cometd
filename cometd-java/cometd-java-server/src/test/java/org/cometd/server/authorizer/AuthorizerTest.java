/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.server.authorizer;

import java.util.List;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.HashMapMessage;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;

public class AuthorizerTest extends AbstractBayeuxClientServerTest
{
    private BayeuxServerImpl bayeux;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
        bayeux.getLogger().setDebugEnabled(true);
    }

    public void testAuthorizersOnSlashStarStar() throws Exception
    {
        bayeux.createIfAbsent("/**", new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                // Grant create and subscribe to all and publishes only to service channels
                channel.addAuthorizer(GrantAuthorizer.GRANT_CREATE_SUBSCRIBE);
                channel.addAuthorizer(new Authorizer()
                {
                    public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message)
                    {
                        if (operation == Operation.PUBLISH && channel.isService())
                            return Result.grant();
                        return Result.ignore();
                    }
                });
            }
        });

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"/foo\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        List<Message.Mutable> messages = HashMapMessage.parseMessages(publish.getResponseContent());
        assertEquals(1, messages.size());
        Message message = messages.get(0);
        assertFalse(message.isSuccessful());

        publish = newBayeuxExchange("[{" +
                "\"channel\": \"/service/foo\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        messages = HashMapMessage.parseMessages(publish.getResponseContent());
        assertEquals(1, messages.size());
        message = messages.get(0);
        assertTrue(message.isSuccessful());
    }

    public void testIgnoringAuthorizerDenies() throws Exception
    {
        String channelName = "/test";
        bayeux.createIfAbsent(channelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(new Authorizer()
                {
                    public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message)
                    {
                        return Result.ignore();
                    }
                });
            }
        });

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        List<Message.Mutable> messages = HashMapMessage.parseMessages(publish.getResponseContent());
        assertEquals(1, messages.size());
        Message message = messages.get(0);
        assertFalse(message.isSuccessful());

        // Check that publishing to another channel does not involve authorizers
        ContentExchange grantedPublish = newBayeuxExchange("[{" +
                "\"channel\": \"/foo\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(grantedPublish);
        assertEquals(HttpExchange.STATUS_COMPLETED, grantedPublish.waitForDone());
        assertEquals(200, grantedPublish.getResponseStatus());

        messages = HashMapMessage.parseMessages(grantedPublish.getResponseContent());
        assertEquals(1, messages.size());
        message = messages.get(0);
        assertTrue(message.isSuccessful());
    }

    public void testNoAuthorizersGrant() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"/test\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        List<Message.Mutable> messages = HashMapMessage.parseMessages(publish.getResponseContent());
        assertEquals(1, messages.size());
        Message message = messages.get(0);
        assertTrue(message.isSuccessful());
    }

    public void testDenyAuthorizerDenies() throws Exception
    {
        bayeux.createIfAbsent("/test/*", new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
            }
        });
        String channelName = "/test/denied";
        bayeux.createIfAbsent(channelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(new Authorizer()
                {
                    public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message)
                    {
                        return Result.deny("test");
                    }
                });
            }
        });

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        List<Message.Mutable> messages = HashMapMessage.parseMessages(publish.getResponseContent());
        assertEquals(1, messages.size());
        Message message = messages.get(0);
        assertFalse(message.isSuccessful());

        // Check that publishing to another channel does not involve authorizers
        ContentExchange grantedPublish = newBayeuxExchange("[{" +
                "\"channel\": \"/foo\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(grantedPublish);
        assertEquals(HttpExchange.STATUS_COMPLETED, grantedPublish.waitForDone());
        assertEquals(200, grantedPublish.getResponseStatus());

        messages = HashMapMessage.parseMessages(grantedPublish.getResponseContent());
        assertEquals(1, messages.size());
        message = messages.get(0);
        assertTrue(message.isSuccessful());
    }

    public void testAddRemoveAuthorizer() throws Exception
    {
        bayeux.createIfAbsent("/test/*", new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_NONE);
            }
        });
        String channelName = "/test/granted";
        bayeux.createIfAbsent(channelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(final ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(new Authorizer()
                {
                    public Result authorize(Operation operation, ChannelId channelId, ServerSession session, ServerMessage message)
                    {
                        channel.removeAuthorizer(this);
                        return Result.grant();
                    }
                });
            }
        });

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        List<Message.Mutable> messages = HashMapMessage.parseMessages(publish.getResponseContent());
        assertEquals(1, messages.size());
        Message message = messages.get(0);
        assertTrue(message.isSuccessful());

        // Check that publishing again fails (the authorizer has been removed)
        ContentExchange grantedPublish = newBayeuxExchange("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(grantedPublish);
        assertEquals(HttpExchange.STATUS_COMPLETED, grantedPublish.waitForDone());
        assertEquals(200, grantedPublish.getResponseStatus());

        messages = HashMapMessage.parseMessages(grantedPublish.getResponseContent());
        assertEquals(1, messages.size());
        message = messages.get(0);
        assertFalse(message.isSuccessful());
    }
}
