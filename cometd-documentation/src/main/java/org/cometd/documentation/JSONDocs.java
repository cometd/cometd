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
package org.cometd.documentation;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.JSONContext;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.JSONContextServer;
import org.cometd.server.JettyJSONContextServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;

@SuppressWarnings("unused")
public class JSONDocs {
    public static void clientConfig(HttpClient httpClient, String cometdURL) {
        // tag::clientConfig[]
        Map<String, Object> transportOptions = new HashMap<>();

        // Use the Jackson implementation
        JSONContext.Client jsonContext = new JacksonJSONContextClient();
        transportOptions.put(ClientTransport.JSON_CONTEXT_OPTION, jsonContext);

        ClientTransport transport = new JettyHttpClientTransport(transportOptions, httpClient);

        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        // end::clientConfig[]
    }

    // tag::readFile[]
    public EchoInfo readFromConfigFile(BayeuxServer bayeuxServer) throws IOException, ParseException {
        try (FileReader reader = new FileReader("echo.json")) {
            JSONContextServer jsonContext = (JSONContextServer)bayeuxServer.getOption(AbstractServerTransport.JSON_CONTEXT_OPTION);
            EchoInfo info = jsonContext.getParser().parse(reader, EchoInfo.class);
            return info;
        }
    }
    // end::readFile[]

    // tag::writeFile[]
    public void writeToConfigFile(BayeuxServer bayeuxServer, EchoInfo info) throws IOException {
        try (FileWriter writer = new FileWriter("echo.json")) {
            JSONContextServer jsonContext = (JSONContextServer)bayeuxServer.getOption(AbstractServerTransport.JSON_CONTEXT_OPTION);
            String json = jsonContext.getGenerator().generate(info);
            writer.write(json);
        }
    }
    // end::writeFile[]

    public static void portability(ServerMessage message) {
        // tag::portability[]
        Map<String, Object> data = message.getDataAsMap();

        // Expecting a JSON array

        // WRONG
        Object[] wrongArray = (Object[])data.get("array");

        // CORRECT
        Object field = data.get("array");
        Object[] array = field instanceof List ? ((List<?>)field).toArray() : (Object[])field;


        // Expecting a long

        // WRONG
        long wrongValue = (Long)data.get("value");

        // CORRECT
        long value = ((Number)data.get("value")).longValue();
        // end::portability[]
    }

    public void configureConvertor(BayeuxServer bayeuxServer) {
        // tag::configureConvertor[]
        JettyJSONContextServer jsonContext = (JettyJSONContextServer)bayeuxServer.getOption(AbstractServerTransport.JSON_CONTEXT_OPTION);
        jsonContext.putConvertor(EchoInfo.class.getName(), new EchoInfoConvertor());
        // end::configureConvertor[]
    }

    // tag::convertor[]
    public class EchoInfoConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
            EchoInfo echoInfo = (EchoInfo)obj;
            out.addClass(EchoInfo.class);
            out.add("id", echoInfo.getId());
            out.add("echo", echoInfo.getEcho());
        }

        @Override
        public Object fromJSON(Map map) {
            String id = (String)map.get("id");
            String echo = (String)map.get("echo");
            return new EchoInfo(id, echo);
        }
    }

    // end::convertor[]
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::convertor[]
    public class EchoInfo {
        private final String id;
        private final String echo;

        public EchoInfo(String id, String echo) {
            this.id = id;
            this.echo = echo;
        }

        public String getId() {
            return id;
        }

        public String getEcho() {
            return echo;
        }
    }
    // end::convertor[]

    public void clientConvertor(BayeuxClient bayeuxClient) {
        // tag::clientConvertor[]
        // At application initialization.
        JettyJSONContextClient jsonContext = new JettyJSONContextClient();
        jsonContext.putConvertor(EchoInfo.class.getName(), new EchoInfoConvertor());

        // Later in the application.
        bayeuxClient.getChannel("/echo").subscribe((channel, message) -> {
            // Receive directly EchoInfo objects.
            EchoInfo data = (EchoInfo)message.getData();
        });

        // Publish directly EchoInfo objects.
        bayeuxClient.getChannel("/echo").publish(new EchoInfo("42", "wohoo"));
        // end::clientConvertor[]
    }
}
