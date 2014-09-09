/*
 * Copyright (c) 2008-2014 the original author or authors.
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

package org.cometd.server.transport;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.transport.JSONPTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class JSONPTransportCallbackParamValidationTest extends AbstractBayeuxClientServerTest {

    final Map<String, String> initParams =
            new HashMap<String, String>(){{put("long-polling.jsonp.callbackParameterMaxLength", "10");}};
    final String jsonpPath = "/?jsonp=";
    final String messagePath = "&message=";

    public JSONPTransportCallbackParamValidationTest(String dummyTransport) {
        super(JSONPTransport.class.getName());
    }

    @Test
    public void testValidCallbackParamLength() throws Exception {
        startServer(initParams);
        cometdURL = cometdURL + jsonpPath + "short";

        testSubscribe(200);
    }

    @Test
    public void testInvalidCallbackParamLength() throws Exception {
        startServer(initParams);
        cometdURL = cometdURL + jsonpPath + "waytoolongforthistest";

        testSubscribe(400);
    }

    @Override
    protected Request newBayeuxRequest(String requestBody) throws UnsupportedEncodingException
    {
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
        Assert.assertEquals(errorCode, response.getStatus());
    }
}
