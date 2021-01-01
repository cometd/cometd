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
package org.cometd.javascript;

import java.util.HashMap;
import java.util.Map;
import org.cometd.annotation.Service;
import org.cometd.annotation.server.RemoteCall;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.BinaryData;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.ext.BinaryExtension;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDRemoteCallTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testRemoteCallWithResult(String transport) throws Exception {
        initCometDServer(transport);

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(cometdServlet.getBayeux());
        String response = "response";
        processor.process(new RemoteCallWithResultService(response));

        evaluateScript("cometd.init({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        Thread.sleep(1000); // Wait for /meta/connect

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");

        String request = "request";
        evaluateScript("cometd.remoteCall('" + RemoteCallWithResultService.CHANNEL + "', '" + request + "', " +
                "function(response) {" +
                "    window.assert(response.successful === true, 'missing successful field');" +
                "    window.assert(response.data === '" + response + "', 'wrong response');" +
                "    latch.countDown();" +
                "});");

        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }

    @Service
    public static class RemoteCallWithResultService {
        private static final String CHANNEL = "/remote_result";
        private final String response;

        public RemoteCallWithResultService(String response) {
            this.response = response;
        }

        @RemoteCall(CHANNEL)
        public void service(RemoteCall.Caller caller, Object data) {
            caller.result(response);
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRemoteCallWithFailure(String transport) throws Exception {
        initCometDServer(transport);

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(cometdServlet.getBayeux());
        String failure = "response";
        processor.process(new RemoteCallWithFailureService(failure));

        evaluateScript("cometd.init({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        Thread.sleep(1000); // Wait for /meta/connect

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");

        String request = "request";
        evaluateScript("cometd.remoteCall('" + RemoteCallWithFailureService.CHANNEL + "', '" + request + "', " +
                "function(response) {" +
                "    window.assert(response.successful === false, 'missing successful field');" +
                "    window.assert(response.data === '" + failure + "', 'wrong response');" +
                "    latch.countDown();" +
                "});");

        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }

    @Service
    public static class RemoteCallWithFailureService {
        private static final String CHANNEL = "/remote_failure";
        private final String failure;

        public RemoteCallWithFailureService(String failure) {
            this.failure = failure;
        }

        @RemoteCall(CHANNEL)
        public void service(RemoteCall.Caller caller, Object data) {
            caller.failure(failure);
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRemoteCallTimeout(String transport) throws Exception {
        initCometDServer(transport);

        long timeout = 1000;
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(cometdServlet.getBayeux());
        boolean processed = processor.process(new RemoteCallTimeoutService(timeout));
        Assertions.assertTrue(processed);

        evaluateScript("cometd.init({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        Thread.sleep(1000); // Wait for /meta/connect

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");

        evaluateScript("" +
                "var calls = 0;" +
                "cometd.remoteCall('" + RemoteCallTimeoutService.CHANNEL + "', {}, " + timeout + ", " +
                "function(response) {" +
                "    ++calls;" +
                "    window.assert(response.successful === false, 'missing successful field');" +
                "    window.assert(response.error !== undefined, 'missing error field');" +
                "    latch.countDown();" +
                "});");

        // Wait enough for the server to actually reply,
        // to verify that the callback is not called twice.
        Thread.sleep(3 * timeout);

        Assertions.assertTrue(latch.await(5000));
        Assertions.assertEquals(1, ((Number)javaScript.get("calls")).intValue());

        disconnect();
    }

    @Service
    public static class RemoteCallTimeoutService {
        public static final String CHANNEL = "remote_timeout";

        private final long timeout;

        public RemoteCallTimeoutService(long timeout) {
            this.timeout = timeout;
        }

        @RemoteCall(CHANNEL)
        public void service(RemoteCall.Caller caller, Object data) {
            new Thread(() -> {
                try {
                    Thread.sleep(2 * timeout);
                    caller.result(new HashMap<>());
                } catch (InterruptedException x) {
                    caller.failure(x);
                }
            }).start();
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRemoteCallWithCustomDataClass(String transport) throws Exception {
        initCometDServer(transport);

        JettyJSONContextServer jsonContext = (JettyJSONContextServer)bayeuxServer.getOption(AbstractServerTransport.JSON_CONTEXT_OPTION);
        jsonContext.getJSON().addConvertor(Custom.class, new CustomConvertor());

        String request = "request";
        String response = "response";

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(cometdServlet.getBayeux());
        boolean processed = processor.process(new RemoteCallWithCustomDataClassService(request, response));
        Assertions.assertTrue(processed);

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");

        evaluateScript("cometd.init({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        Thread.sleep(1000); // Wait for /meta/connect

        evaluateScript("" +
                "cometd.remoteCall('" + RemoteCallWithCustomDataClassService.CHANNEL + "', {" +
                "   class: '" + Custom.class.getName() + "'," +
                "   payload: '" + request + "'," +
                "}, " +
                "function(response) {" +
                "    window.assert(response.successful === true);" +
                "    window.assert(response.data !== undefined);" +
                "    window.assert(response.data.payload == '" + response + "');" +
                "    latch.countDown();" +
                "});");

        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }

    @Service
    public static class RemoteCallWithCustomDataClassService {
        public static final String CHANNEL = "/custom_data_class";

        private String request;
        private String response;

        public RemoteCallWithCustomDataClassService(String request, String response) {
            this.request = request;
            this.response = response;
        }

        @RemoteCall(CHANNEL)
        public void service(RemoteCall.Caller caller, Custom custom) {
            if (request.equals(custom.payload)) {
                caller.result(new Custom(response));
            } else {
                caller.failure("failed");
            }
        }
    }

    public static class Custom {
        public final String payload;

        public Custom(String payload) {
            this.payload = payload;
        }
    }

    private class CustomConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
            Custom custom = (Custom)obj;
            out.add("payload", custom.payload);
        }

        @Override
        public Object fromJSON(Map object) {
            return new Custom((String)object.get("payload"));
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRemoteCallBinary(String transport) throws Exception {
        initCometDServer(transport);

        bayeuxServer.addExtension(new BinaryExtension());
        provideBinaryExtension();

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeuxServer);
        processor.process(new RemoteCallBinaryService());

        evaluateScript("cometd.init({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        Thread.sleep(1000); // Wait for /meta/connect

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");

        evaluateScript("" +
                "var buffer = new ArrayBuffer(16);" +
                "var view1 = new DataView(buffer);" +
                "for (var d = 0; d < view1.byteLength; ++d) {" +
                "    view1.setUint8(d, d);" +
                "}" +
                "var meta = {" +
                "    contentType: 'application/octet-stream'" +
                "};" +
                "cometd.remoteCallBinary('" + RemoteCallBinaryService.CHANNEL + "', view1, true, meta, function(response) {" +
                "    window.assert(response.successful === true, 'missing successful field');" +
                "    var data = response.data;" +
                "    window.assert(meta.contentType === data.meta.contentType);" +
                "    var view2 = new DataView(data.data);" +
                "    for (var i = 0; i < view1.byteLength; ++i) {" +
                "        window.assert(view2.getUint8(i) === view1.getUint8(i));" +
                "    }" +
                "    latch.countDown();" +
                "});");

        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }

    @Service
    public static class RemoteCallBinaryService {
        public static final String CHANNEL = "/binary";

        @RemoteCall(CHANNEL)
        public void onBinary(RemoteCall.Caller caller, BinaryData data) {
            caller.result(data);
        }
    }
}
