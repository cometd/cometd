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
package org.cometd.client;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.cometd.client.transport.LongPollingTransport;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.Assert;
import org.junit.Test;

public class HandshakeWithAuthenticationTest extends ClientServerTest {
    @Test
    public void testHandshakeWithBasicAuthenticationHeaders() throws Exception {
        startServer(null);
        int port = connector.getLocalPort();
        server.stop();

        final String userName = "cometd";
        final String password = "cometd";
        String role = "admin";
        Path authProps = Files.createTempFile("cometd-autentication", ".properties");
        try (BufferedWriter writer = Files.newBufferedWriter(authProps, StandardCharsets.UTF_8)) {
            writer.write(String.format("%s:%s,%s", userName, password, role));
        }
        HashLoginService loginService = new HashLoginService("CometD-Realm");
        loginService.setConfig(authProps.toString());
        server.addBean(loginService);

        Handler handler = server.getHandler();
        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        server.setHandler(security);
        security.setHandler(handler);

        Constraint constraint = new Constraint();
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{role});

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec(cometdServletPath + "/*");
        mapping.setConstraint(constraint);

        security.setConstraintMappings(Collections.singletonList(mapping));
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);

        connector.setPort(port);
        server.start();

        LongPollingTransport transport = new LongPollingTransport(null, httpClient) {
            @Override
            protected void customize(Request request) {
                String authorization = userName + ":" + password;
                authorization = B64Code.encode(authorization);
                request.header("Authorization", "Basic " + authorization);
            }
        };
        BayeuxClient client = new BayeuxClient(cometdURL, transport);

        client.handshake();

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Allow long poll to establish
        Thread.sleep(1000);

        disconnectBayeuxClient(client);
    }
}
