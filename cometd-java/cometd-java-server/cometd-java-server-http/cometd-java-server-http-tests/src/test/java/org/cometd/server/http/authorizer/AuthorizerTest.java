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
package org.cometd.server.http.authorizer;

import java.util.List;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.http.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class AuthorizerTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testAuthorizersOnSlashStarStar(Transport transport) throws Exception {
        startServer(transport, null);

        bayeux.createChannelIfAbsent("/**", channel -> {
            // Grant create and subscribe to all and publishes only to service channels
            channel.addAuthorizer(GrantAuthorizer.GRANT_CREATE_SUBSCRIBE);
            channel.addAuthorizer((operation, channel1, session, message) -> {
                if (operation == Authorizer.Operation.PUBLISH && channel1.isService()) {
                    return Authorizer.Result.grant();
                }
                return Authorizer.Result.ignore();
            });
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

        Request publish = newBayeuxRequest("[{" +
                                           "\"channel\": \"/foo\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"data\": {}" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        List<Message.Mutable> messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        Message message = messages.get(0);
        Assertions.assertFalse(message.isSuccessful());

        publish = newBayeuxRequest("[{" +
                                   "\"channel\": \"/service/foo\"," +
                                   "\"clientId\": \"" + clientId + "\"," +
                                   "\"data\": {}" +
                                   "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        message = messages.get(0);
        Assertions.assertTrue(message.isSuccessful());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIgnoringAuthorizerDenies(Transport transport) throws Exception {
        startServer(transport, null);

        String channelName = "/test";
        bayeux.createChannelIfAbsent(channelName, channel ->
                channel.addAuthorizer((operation, channel1, session, message) -> Authorizer.Result.ignore()));

        Request handshake = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                                           "\"channel\": \"" + channelName + "\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"data\": {}" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        List<Message.Mutable> messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        Message message = messages.get(0);
        Assertions.assertFalse(message.isSuccessful());

        // Check that publishing to another channel does not involve authorizers
        Request grantedPublish = newBayeuxRequest("[{" +
                                                  "\"channel\": \"/foo\"," +
                                                  "\"clientId\": \"" + clientId + "\"," +
                                                  "\"data\": {}" +
                                                  "}]");
        response = grantedPublish.send();
        Assertions.assertEquals(200, response.getStatus());

        messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        message = messages.get(0);
        Assertions.assertTrue(message.isSuccessful());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNoAuthorizersGrant(Transport transport) throws Exception {
        startServer(transport, null);

        Request handshake = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                                           "\"channel\": \"/test\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"data\": {}" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        List<Message.Mutable> messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        Message message = messages.get(0);
        Assertions.assertTrue(message.isSuccessful());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDenyAuthorizerDenies(Transport transport) throws Exception {
        startServer(transport, null);

        bayeux.createChannelIfAbsent("/test/*", channel -> channel.addAuthorizer(GrantAuthorizer.GRANT_ALL));
        String channelName = "/test/denied";
        bayeux.createChannelIfAbsent(channelName, channel ->
                channel.addAuthorizer((operation, channel1, session, message) -> Authorizer.Result.deny("test")));

        Request handshake = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                                           "\"channel\": \"" + channelName + "\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"data\": {}" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        List<Message.Mutable> messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        Message message = messages.get(0);
        Assertions.assertFalse(message.isSuccessful());

        // Check that publishing to another channel does not involve authorizers
        Request grantedPublish = newBayeuxRequest("[{" +
                                                  "\"channel\": \"/foo\"," +
                                                  "\"clientId\": \"" + clientId + "\"," +
                                                  "\"data\": {}" +
                                                  "}]");
        response = grantedPublish.send();
        Assertions.assertEquals(200, response.getStatus());

        messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        message = messages.get(0);
        Assertions.assertTrue(message.isSuccessful());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAddRemoveAuthorizer(Transport transport) throws Exception {
        startServer(transport, null);

        bayeux.createChannelIfAbsent("/test/*", channel -> channel.addAuthorizer(GrantAuthorizer.GRANT_NONE));
        String channelName = "/test/granted";
        bayeux.createChannelIfAbsent(channelName, new ConfigurableServerChannel.Initializer() {
            @Override
            public void configureChannel(ConfigurableServerChannel channel) {
                channel.addAuthorizer(new Authorizer() {
                    @Override
                    public Result authorize(Operation operation, ChannelId channelId, ServerSession session, ServerMessage message) {
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
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                                           "\"channel\": \"" + channelName + "\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"data\": {}" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        List<Message.Mutable> messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        Message message = messages.get(0);
        Assertions.assertTrue(message.isSuccessful());

        // Check that publishing again fails (the authorizer has been removed)
        Request grantedPublish = newBayeuxRequest("[{" +
                                                  "\"channel\": \"" + channelName + "\"," +
                                                  "\"clientId\": \"" + clientId + "\"," +
                                                  "\"data\": {}" +
                                                  "}]");
        response = grantedPublish.send();
        Assertions.assertEquals(200, response.getStatus());

        messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());
        message = messages.get(0);
        Assertions.assertFalse(message.isSuccessful());
    }
}
