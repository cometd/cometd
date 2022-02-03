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
package org.cometd.oort;

import java.util.HashMap;
import java.util.Map;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.JacksonJSONContextServer;
import org.eclipse.jetty.server.Server;

public class JacksonOortObjectTest extends OortObjectTest {
    @Override
    protected Server startServer(String serverTransport, int port, Map<String, String> options) throws Exception {
        if (options == null) {
            options = new HashMap<>();
        }
        options.put(AbstractServerTransport.JSON_CONTEXT_OPTION, JacksonJSONContextServer.class.getName());
        return super.startServer(serverTransport, port, options);
    }

    @Override
    protected Oort startOort(Server server) throws Exception {
        Oort oort = super.startOort(server);
        oort.setJSONContextClient(new JacksonJSONContextClient());
        return oort;
    }
}
