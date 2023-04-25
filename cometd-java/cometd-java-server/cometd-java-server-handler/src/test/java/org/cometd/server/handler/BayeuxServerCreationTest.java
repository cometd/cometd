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
package org.cometd.server.handler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.handler.transport.AsyncJSONTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BayeuxServerCreationTest
{
    @Test
    public void testCreationWithoutOptions() throws Exception {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();
        bayeuxServer.start();

        Set<String> knownTransports = bayeuxServer.getKnownTransportNames();
        Assertions.assertEquals(1, knownTransports.size());
        Assertions.assertTrue(knownTransports.contains(AsyncJSONTransport.NAME));
        Assertions.assertEquals(knownTransports, new HashSet<>(bayeuxServer.getAllowedTransports()));
    }

    @Test
    public void testCreationWithOptions() throws Exception {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();

        Map<String, String> options = new HashMap<>();
        String timeoutKey = "timeout";
        String timeoutValue = "10007";
        options.put(timeoutKey, timeoutValue);
        String longPollingTimeoutKey = "long-polling.timeout";
        String longPollingTimeoutValue = "11047";
        options.put(longPollingTimeoutKey, longPollingTimeoutValue);
        String websocketTimeoutKey = "ws.timeout";
        String websocketTimeoutValue = "12041";
        options.put(websocketTimeoutKey, websocketTimeoutValue);
        String jsonTimeoutKey = "long-polling.json.timeout";
        String jsonTimeoutValue = "13003";
        options.put(jsonTimeoutKey, jsonTimeoutValue);
        String jsonpTimeoutKey = "long-polling.jsonp.timeout";
        String jsonpTimeoutValue = "14009";
        options.put(jsonpTimeoutKey, jsonpTimeoutValue);

        for (Map.Entry<String, String> entry : options.entrySet()) {
            bayeuxServer.setOption(entry.getKey(), entry.getValue());
        }

        bayeuxServer.start();

        Assertions.assertEquals(timeoutValue, bayeuxServer.getOption(timeoutKey));
        Assertions.assertEquals(jsonTimeoutValue, bayeuxServer.getTransport(AsyncJSONTransport.NAME).getOption(timeoutKey));
    }

    @Test
    public void testCreationWithTransports() throws Exception {
        BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();

        AsyncJSONTransport jsonTransport = new AsyncJSONTransport(bayeuxServer);
        long timeout = 13003L;
        jsonTransport.setTimeout(timeout);
        bayeuxServer.setTransports(jsonTransport);
        bayeuxServer.setAllowedTransports(AsyncJSONTransport.NAME);

        bayeuxServer.start();

        Assertions.assertEquals(1, bayeuxServer.getAllowedTransports().size());
        Assertions.assertEquals(1, bayeuxServer.getKnownTransportNames().size());
        Assertions.assertEquals(AsyncJSONTransport.NAME, bayeuxServer.getAllowedTransports().get(0));
        Assertions.assertEquals(timeout, bayeuxServer.getTransport(AsyncJSONTransport.NAME).getTimeout());
    }
}
