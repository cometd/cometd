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

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(cometdServlet.getBayeuxServer());
        String response = "response";
        processor.process(new RemoteCallWithResultService(response));


        evaluateScript("cometd.init({url: '$U', logLevel: '$L'});"
                .replace("$U", cometdURL).replace("$L", getLogLevel()));
        Thread.sleep(1000); // Wait for /meta/connect

        String request = "request";
        evaluateScript("""
                const latch = new Latch(1);
                cometd.remoteCall('$C', '$D', response => {
                    window.assert(response.successful === true, 'missing successful field');
                    window.assert(response.data === '$R', 'wrong response');
                    latch.countDown();
                });
                """.replace("$C", RemoteCallWithResultService.CHANNEL)
                .replace("$D", request)
                .replace("$R", response));

        Latch latch = javaScript.get("latch");
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

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(cometdServlet.getBayeuxServer());
        String failure = "response";
        processor.process(new RemoteCallWithFailureService(failure));

        evaluateScript("cometd.init({url: '$U', logLevel: '$L'});"
                .replace("$U", cometdURL).replace("$L", getLogLevel()));
        Thread.sleep(1000); // Wait for /meta/connect

        String request = "request";
        evaluateScript("""
                const latch = new Latch(1);
                cometd.remoteCall('$C', '$D', response => {
                    window.assert(response.successful === false, 'missing successful field');
                    window.assert(response.data === '$R', 'wrong response');
                    latch.countDown();
                });
                """.replace("$C", RemoteCallWithFailureService.CHANNEL)
                .replace("$D", request)
                .replace("$R", failure));

        Latch latch = javaScript.get("latch");
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
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(cometdServlet.getBayeuxServer());
        boolean processed = processor.process(new RemoteCallTimeoutService(timeout));
        Assertions.assertTrue(processed);

        evaluateScript("cometd.init({url: '$U', logLevel: '$L'});"
                .replace("$U", cometdURL).replace("$L", getLogLevel()));
        Thread.sleep(1000); // Wait for /meta/connect

        evaluateScript("""
                const latch = new Latch(1);
                let calls = 0;
                cometd.remoteCall('$C', {}, $T, response => {
                    ++calls;
                    window.assert(response.successful === false, 'missing successful field');
                    window.assert(response.error !== undefined, 'missing error field');
                    latch.countDown();
                });
                """.replace("$C", RemoteCallTimeoutService.CHANNEL)
                .replace("$T", String.valueOf(timeout)));
        // Wait enough for the server to actually reply,
        // to verify that the callback is not called twice.
        Thread.sleep(3 * timeout);

        Latch latch = javaScript.get("latch");
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

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(cometdServlet.getBayeuxServer());
        boolean processed = processor.process(new RemoteCallWithCustomDataClassService(request, response));
        Assertions.assertTrue(processed);

        evaluateScript("cometd.init({url: '$U', logLevel: '$L'});"
                .replace("$U", cometdURL).replace("$L", getLogLevel()));
        Thread.sleep(1000); // Wait for /meta/connect

        evaluateScript("""
                const latch = new Latch(1);
                cometd.remoteCall('$C', {
                    class: '$T',
                    payload: '$D',
                }, response => {
                    window.assert(response.successful === true);
                    window.assert(response.data !== undefined);
                    window.assert(response.data.payload == '$R');
                    latch.countDown();
                });
                """.replace("$C", RemoteCallWithCustomDataClassService.CHANNEL)
                .replace("$T", Custom.class.getName())
                .replace("$D", request)
                .replace("$R", response));

        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }

    @Service
    public static class RemoteCallWithCustomDataClassService {
        public static final String CHANNEL = "/custom_data_class";

        private final String request;
        private final String response;

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

    private static class CustomConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
            Custom custom = (Custom)obj;
            out.add("payload", custom.payload);
        }

        @Override
        public Object fromJSON(Map<String, Object> object) {
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

        evaluateScript("cometd.init({url: '$U', logLevel: '$L'});"
                .replace("$U", cometdURL).replace("$L", getLogLevel()));
        Thread.sleep(1000); // Wait for /meta/connect

        evaluateScript("""
                const latch = new Latch(1);
                const buffer = new ArrayBuffer(16);
                const view1 = new DataView(buffer);
                for (let d = 0; d < view1.byteLength; ++d) {
                    view1.setUint8(d, d);
                }
                const meta = {
                    contentType: 'application/octet-stream'
                };
                cometd.remoteCallBinary('$C', view1, true, meta, response => {
                    window.assert(response.successful === true, 'missing successful field');
                    const data = response.data;
                    window.assert(meta.contentType === data.meta.contentType);
                    const view2 = new DataView(data.data);
                    for (let i = 0; i < view1.byteLength; ++i) {
                        window.assert(view2.getUint8(i) === view1.getUint8(i));
                    }
                    latch.countDown();
                });
                """.replace("$C", RemoteCallBinaryService.CHANNEL));

        Latch latch = javaScript.get("latch");
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
