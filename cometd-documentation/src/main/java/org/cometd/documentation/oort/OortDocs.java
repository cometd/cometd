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
package org.cometd.documentation.oort;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.oort.Oort;
import org.cometd.oort.OortComet;
import org.cometd.server.DefaultSecurityPolicy;

@SuppressWarnings("unused")
public class OortDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::config[]
    public class OortConfigurationServlet extends HttpServlet {
        @Override
        public void init() throws ServletException {
            // Grab the Oort object
            Oort oort = (Oort)getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);

            // Figure out the URLs to connect to, using other discovery means
            List<String> urls = discoverNodeURLs();

            // Connect to the other Oort nodes
            for (String url : urls) {
                OortComet oortComet = oort.observeComet(url);
                if (!oortComet.waitFor(1000, BayeuxClient.State.CONNECTED)) {
                    throw new ServletException("Cannot connect to Oort node " + url);
                }
            }
        }

        @Override
        public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
            throw new ServletException();
        }
    }
    // end::config[]

    private List<String> discoverNodeURLs() {
        return List.of();
    }

    public static void cometListener(Oort oort) {
        // tag::cometListener[]
        oort.addCometListener(new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                System.out.printf("Node joined the cluster %s%n", event.getCometURL());
            }

            @Override
            public void cometLeft(Event event) {
                System.out.printf("Node left the cluster %s%n", event.getCometURL());
            }
        });
        // end::cometListener[]
    }

    public static void cometJoined(Oort oort) {
        // tag::cometJoined[]
        oort.addCometListener(new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                String cometURL = event.getCometURL();
                OortComet oortComet = oort.observeComet(cometURL);

                // Push information to the new node
                oortComet.getChannel("/service/foo").publish("bar");
            }
        });
        // end::cometJoined[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::policy[]
    public class OortSecurityPolicy extends DefaultSecurityPolicy {
        private final Oort oort;

        private OortSecurityPolicy(Oort oort) {
            this.oort = oort;
        }

        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            // Local sessions can always handshake.
            if (session.isLocalSession()) {
                promise.succeed(true);
                return;
            }

            // Remote Oort nodes are allowed to handshake.
            if (oort.isOortHandshake(message)) {
                promise.succeed(true);
                return;
            }

            // Remote clients must have a valid token.
            Map<String, Object> ext = message.getExt();
            boolean valid = ext != null && isValid(ext.get("token"));
            promise.succeed(valid);
        }
    }
    // end::policy[]

    private boolean isValid(Object token) {
        return false;
    }
}
