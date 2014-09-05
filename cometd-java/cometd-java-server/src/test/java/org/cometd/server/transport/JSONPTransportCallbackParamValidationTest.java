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
