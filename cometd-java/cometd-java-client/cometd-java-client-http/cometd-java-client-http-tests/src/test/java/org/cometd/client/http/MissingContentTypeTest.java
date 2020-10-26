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
package org.cometd.client.http;

import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MissingContentTypeTest extends ClientServerTest {
    @Test
    public void testMissingContentType() throws Exception {
        start(null);

        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient) {
            @Override
            protected void customize(Request request) {
                request.header(HttpHeader.CONTENT_TYPE, null);
            }
        });

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for long poll to establish
        Thread.sleep(1000);

        disconnectBayeuxClient(client);
    }
}
