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

import java.util.Map;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;

public class HTTP2ClientServerTest extends ClientServerTest {
    @Override
    protected void startServer(Map<String, String> initParams, ConnectionFactory... connectionFactories) throws Exception {
        HttpConfiguration httpConfig = new HttpConfiguration();
        super.startServer(initParams, new HttpConnectionFactory(httpConfig), new HTTP2CServerConnectionFactory(httpConfig));
    }

    @Override
    protected void startClient() throws Exception {
        httpClient = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client()), null);
        httpClient.start();
    }
}
