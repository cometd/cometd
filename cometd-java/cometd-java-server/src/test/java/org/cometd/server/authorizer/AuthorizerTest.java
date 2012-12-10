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

import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class AuthorizerTest extends AbstractBayeuxClientServerTest
{
    @Test
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

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"/foo\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        Message message = messages[0];
        Assert.assertFalse(message.isSuccessful());

        publish = newBayeuxRequest("[{" +
                "\"channel\": \"/service/foo\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        message = messages[0];
        Assert.assertTrue(message.isSuccessful());
    }

    @Test
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

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        Message message = messages[0];
        Assert.assertFalse(message.isSuccessful());

        // Check that publishing to another channel does not involve authorizers
        Request grantedPublish = newBayeuxRequest("[{" +
                "\"channel\": \"/foo\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = grantedPublish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        message = messages[0];
        Assert.assertTrue(message.isSuccessful());
    }

    @Test
    public void testNoAuthorizersGrant() throws Exception
    {
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"/test\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        Message message = messages[0];
        Assert.assertTrue(message.isSuccessful());
    }

    @Test
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

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        Message message = messages[0];
        Assert.assertFalse(message.isSuccessful());

        // Check that publishing to another channel does not involve authorizers
        Request grantedPublish = newBayeuxRequest("[{" +
                "\"channel\": \"/foo\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = grantedPublish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        message = messages[0];
        Assert.assertTrue(message.isSuccessful());
    }

    @Test
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

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        Message message = messages[0];
        Assert.assertTrue(message.isSuccessful());

        // Check that publishing again fails (the authorizer has been removed)
        Request grantedPublish = newBayeuxRequest("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = grantedPublish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        message = messages[0];
        Assert.assertFalse(message.isSuccessful());
    }
}
