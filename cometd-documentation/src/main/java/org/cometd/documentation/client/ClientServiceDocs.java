/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import java.util.HashMap;
import java.util.Map;

import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.Subscription;
import org.cometd.annotation.client.ClientAnnotationProcessor;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.eclipse.jetty.client.HttpClient;

@SuppressWarnings("unused")
public class ClientServiceDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotated[]
    @Service
    public class ClientService {
        @Session
        private ClientSession bayeuxClient;

        @Listener(Channel.META_CONNECT)
        public void metaConnect(Message connect) {
            // Connect handling...
        }

        @Subscription("/foo")
        public void foo(Message message) {
            // Message handling...
        }
    }
    // end::annotated[]

    public static void traditional() {
        HttpClient httpClient = new HttpClient();
        String cometdServerURL = "http://localhost:8080/cometd";
        Map<String, Object> options = new HashMap<>();
        // tag::traditional[]
        BayeuxClient bayeuxClient = new BayeuxClient(cometdServerURL, new JettyHttpClientTransport(options, httpClient));

        bayeuxClient.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            // Connect handling...
        });

        bayeuxClient.handshake(reply ->
        {
            if (reply.isSuccessful()) {
                bayeuxClient.getChannel("/foo").subscribe((channel, message) -> {
                    // Message handling...
                });
            }
        });
        // end::traditional[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedService[]
    @Service
    public class ServiceWithBayeuxClient {
        @org.cometd.annotation.Session
        private BayeuxClient bayeuxClient;
    }
    // end::annotatedService[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedListener[]
    @Service
    public class ServiceWithListener {
        @Listener(Channel.META_CONNECT)
        public void metaConnect(Message connect) {
            // Connect handling...
        }
    }
    // end::annotatedListener[]

    public static void traditionalListener() {
        HttpClient httpClient = new HttpClient();
        String cometdServerURL = "http://localhost:8080/cometd";
        Map<String, Object> options = new HashMap<>();
        BayeuxClient bayeuxClient = new BayeuxClient(cometdServerURL, new JettyHttpClientTransport(options, httpClient));
        // tag::traditionalListener[]
        bayeuxClient.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            // Connect handling...
        });
        // end::traditionalListener[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedSubscription[]
    @Service
    public class ServiceWithSubscription {
        @Subscription("/foo/*")
        public void foo(Message message) {
            // Message handling...
        }
    }
    // end::annotatedSubscription[]

    public static void traditionalSubscription() {
        HttpClient httpClient = new HttpClient();
        String cometdServerURL = "http://localhost:8080/cometd";
        Map<String, Object> options = new HashMap<>();
        BayeuxClient bayeuxClient = new BayeuxClient(cometdServerURL, new JettyHttpClientTransport(options, httpClient));
        // tag::traditionalSubscription[]
        bayeuxClient.getChannel("/foo/*").subscribe((channel, message) -> {
            // Message handling...
        });
        // end::traditionalSubscription[]
    }

    public void annotationProcessing() {
        HttpClient httpClient = new HttpClient();
        String cometdServerURL = "http://localhost:8080/cometd";
        Map<String, Object> options = new HashMap<>();
        // tag::annotationProcessing[]
        BayeuxClient bayeuxClient = new BayeuxClient(cometdServerURL, new JettyHttpClientTransport(options, httpClient));

        // Create the ClientAnnotationProcessor.
        ClientAnnotationProcessor processor = new ClientAnnotationProcessor(bayeuxClient);

        // Create the service instance.
        ClientService service = new ClientService();

        // Process the annotated service.
        processor.process(service);

        // Now BayeuxClient can handshake with the CometD server.
        bayeuxClient.handshake();
        // end::annotationProcessing[]
    }
}
