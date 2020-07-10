/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.eclipse.jetty.client.HttpClient;

@SuppressWarnings("unused")
public class ClientDisconnectDocs {
    private final HttpClient httpClient = new HttpClient();
    private final JettyHttpClientTransport transport = new JettyHttpClientTransport(null, httpClient);

    public void simpleDisconnect() {
        // tag::simpleDisconnect[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        bayeuxClient.disconnect();
        // end::simpleDisconnect[]
    }

    public void callbackDisconnect() {
        // tag::callbackDisconnect[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        bayeuxClient.disconnect(disconnectReply -> {
            if (disconnectReply.isSuccessful()) {
                // The server processed the disconnect request.
            }
        });
        // end::callbackDisconnect[]
    }

    public void blockingDisconnect() {
        // tag::blockingDisconnect[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        bayeuxClient.disconnect();
        boolean disconnected = bayeuxClient.waitFor(1000, BayeuxClient.State.DISCONNECTED);
        // end::blockingDisconnect[]
    }
}
