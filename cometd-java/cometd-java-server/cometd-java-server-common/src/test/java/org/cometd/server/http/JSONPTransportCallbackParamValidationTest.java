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
package org.cometd.server.http;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.cometd.server.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JSONPTransportCallbackParamValidationTest extends AbstractBayeuxClientServerTest {
    // We want to test only the JSONPTransport.
    private final String serverTransport = JSONPTransport.class.getName();
    private final Map<String, String> initParams = new HashMap<>();
    private final String jsonpPath = "/?jsonp=";

    public JSONPTransportCallbackParamValidationTest() {
        initParams.put("long-polling.jsonp.callbackParameterMaxLength", "10");
    }

    @Test
    public void testValidCallbackParamLength() throws Exception {
        startServer(serverTransport, initParams);
        cometdURL = cometdURL + jsonpPath + "short";
        testSubscribe(200);
    }

    @Test
    public void testInvalidCallbackParamLength() throws Exception {
        startServer(serverTransport, initParams);
        cometdURL = cometdURL + jsonpPath + "waytoolongforthistest";
        testSubscribe(400);
    }

    @Test
    public void testValidCallbackParamCharacters() throws Exception {
        startServer(serverTransport, initParams);
        cometdURL = cometdURL + jsonpPath + "s-h_o.R1";
        testSubscribe(200);
    }

    @Test
    public void testInvalidCallbackParamCharacters() throws Exception {
        startServer(serverTransport, initParams);
        cometdURL = cometdURL + jsonpPath + "sh%20rt";
        testSubscribe(400);
        cometdURL = cometdURL + jsonpPath + "sh%0d%0art";
        testSubscribe(400);
        cometdURL = cometdURL + jsonpPath + "NOTICE:";
        testSubscribe(400);
    }

    @Override
    protected Request newBayeuxRequest(String requestBody) throws UnsupportedEncodingException {
        String messagePath = "&message=";
        Request request = httpClient.newRequest(cometdURL + messagePath + URLEncoder.encode(requestBody, "UTF-8"));
        request.timeout(5, TimeUnit.SECONDS);
        request.method(HttpMethod.GET);
        return request;
    }

    private void testSubscribe(int errorCode) throws UnsupportedEncodingException, InterruptedException, TimeoutException, ExecutionException {
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"callback-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(errorCode, response.getStatus());
    }
}
