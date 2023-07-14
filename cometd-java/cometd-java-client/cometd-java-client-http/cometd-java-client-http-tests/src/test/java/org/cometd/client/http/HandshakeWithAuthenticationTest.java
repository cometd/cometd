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
package org.cometd.client.http;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.security.Constraint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HandshakeWithAuthenticationTest extends ClientServerTest {
    @Test
    public void testHandshakeWithBasicAuthenticationHeaders() throws Exception {
        start(null);
        int port = connector.getLocalPort();
        server.stop();

        String userName = "cometd";
        String password = "cometd";
        String role = "admin";
        Path authProps = Files.createTempFile("cometd-autentication", ".properties");
        try (BufferedWriter writer = Files.newBufferedWriter(authProps, StandardCharsets.UTF_8)) {
            writer.write(String.format("%s:%s,%s", userName, password, role));
        }
        HashLoginService loginService = new HashLoginService("CometD-Realm");
        loginService.setConfig(ResourceFactory.of(server).newResource(authProps));
        server.addBean(loginService);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        context.setSecurityHandler(security);

        Constraint constraint = Constraint.from(role);

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec(cometdServletPath + "/*");
        mapping.setConstraint(constraint);

        security.setConstraintMappings(List.of(mapping));
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);

        connector.setPort(port);
        server.start();
        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        ClientTransport transport = new JettyHttpClientTransport(null, httpClient) {
            @Override
            protected void customize(Request request) {
                String authorization = userName + ":" + password;
                byte[] bytes = Base64.getEncoder().encode(authorization.getBytes(StandardCharsets.UTF_8));
                String encoded = new String(bytes, StandardCharsets.UTF_8);
                request.headers(headers -> headers.put("Authorization", "Basic " + encoded));
            }
        };
        BayeuxClient client = new BayeuxClient(cometdURL, transport);

        CountDownLatch connectLatch = new CountDownLatch(1);
        client.handshake(message -> {
            if (message.isSuccessful()) {
                ServerSession session = bayeux.getSession(message.getClientId());
                session.addListener(new ServerSession.HeartBeatListener() {
                    @Override
                    public void onSuspended(ServerSession session, ServerMessage message, long timeout) {
                        connectLatch.countDown();
                    }
                });
            }
        });

        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
