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
package org.cometd.documentation.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cometd.bayeux.BinaryData;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.eclipse.jetty.client.HttpClient;

@SuppressWarnings("unused")
public class ClientSendDocs {
    public void simplePublish() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        // tag::simplePublish[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        bayeuxClient.handshake(handshakeReply -> {
            // You can only publish after a successful handshake.
            if (handshakeReply.isSuccessful()) {
                // The structure that holds the data you want to publish.
                Map<String, Object> data = new HashMap<>();
                // Fill in the structure, for example:
                data.put("move", "e4");
                // The channel onto which publish the data.
                ClientSessionChannel channel = bayeuxClient.getChannel("/game/table/1");
                channel.publish(data);
            }
        });
        // end::simplePublish[]
    }

    public void callbackPublish() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        // tag::callbackPublish[]
        Map<String, Object> data = new HashMap<>();
        // Fill in the structure, for example:
        data.put("move", "e4");

        ClientSessionChannel channel = bayeuxClient.getChannel("/game/table/1");
        // Publish the data and receive the publish acknowledgment.
        channel.publish(data, publishReply -> {
            if (publishReply.isSuccessful()) {
                // The message reached the server.
            }
        });
        // end::callbackPublish[]
    }

    public void batchPublish() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        // tag::batchPublish[]
        bayeuxClient.batch(() -> {
            Map<String, Object> data1 = new HashMap<>();
            // Fill in the data1 structure.
            bayeuxClient.getChannel("/game/table/1").publish(data1);

            Map<String, Object> data2 = new HashMap<>();
            // Fill in the data2 structure.
            bayeuxClient.getChannel("/game/chat/1").publish(data2);
        });
        // end::batchPublish[]
    }

    public void binaryPublish() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        // tag::binaryPublish[]
        // Generate the bytes to publish.
        byte[] bytes = MessageDigest.getInstance("MD5").digest("hello".getBytes());

        // Wrap the bytes into BinaryData and publish it.
        bayeuxClient.getChannel("/channel").publish(new BinaryData(bytes, true, null));
        // end::binaryPublish[]
    }

    public void remoteCall() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        // tag::remoteCall[]
        // The data to send to the server.
        List<String> newItems = Arrays.asList("item1", "item2");

        // Call the server.
        bayeuxClient.remoteCall("/items/save", newItems, message -> {
            if (message.isSuccessful()) {
                String result = (String)message.getData();
                // The remote call succeeded, use the result sent by the server.
            } else {
                // The remote call failed.
            }
        });
        // end::remoteCall[]
    }

    public void binaryRemoteCall() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        // tag::binaryRemoteCall[]
        // The buffer to send to the server.
        ByteBuffer buffer = StandardCharsets.UTF_8.encode("hello!");

        // Call the server.
        bayeuxClient.remoteCall("target", new BinaryData(buffer, true, null), message -> {
            if (message.isSuccessful()) {
                // The remote call succeeded, use the result sent by the server.
            } else {
                // The remote call failed.
            }
        });
        // end::binaryRemoteCall[]
    }
}
