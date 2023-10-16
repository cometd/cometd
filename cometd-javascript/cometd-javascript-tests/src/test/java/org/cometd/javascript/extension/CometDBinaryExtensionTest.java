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
package org.cometd.javascript.extension;

import java.nio.ByteBuffer;

import org.cometd.bayeux.BinaryData;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.javascript.AbstractCometDTransportsTest;
import org.cometd.javascript.Latch;
import org.cometd.server.AbstractService;
import org.cometd.server.ext.BinaryExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDBinaryExtensionTest extends AbstractCometDTransportsTest {
    @Override
    public void initCometDServer(String transport) throws Exception {
        super.initCometDServer(transport);
        bayeuxServer.addExtension(new BinaryExtension());
        provideBinaryExtension();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testZ85Basic(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                const binaryExt = cometd.getExtension('binary');
                const payload = 'HelloWorld';
                const m1 = {
                    channel: '/binary',
                    ext: {
                        binary: {}
                    },
                    data: {
                        data: payload,
                        last: true
                    }
                };
                const m2 = binaryExt.incoming(m1);

                window.assert(m2.data.data instanceof ArrayBuffer);

                const control = [134, 79, 210, 111, 181, 89, 247, 91];
                const view = new DataView(m2.data.data);
                for (let i = 0; i < control.length; ++i) {
                    window.assert(view.getUint8(i) === control[i]);
                }
                const m3 = binaryExt.outgoing(m2);

                window.assert(m3.data.data === payload);
                """);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testZ85Unpadded(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                const binaryExt = cometd.getExtension('binary');

                const payload = [0xC, 0x0, 0xF, 0xF, 0xE, 0xE];
                const buffer = new ArrayBuffer(payload.length);
                const view1 = new DataView(buffer);
                for (let i = 0; i < payload.length; ++i) {
                    view1.setUint8(i, payload[i]);
                }

                const m1 = {
                    channel: '/binary',
                    ext: {
                        binary: {}
                    },
                    data: {
                        data: buffer,
                        last: true
                    }
                };
                const m2 = binaryExt.outgoing(m1);
                const m3 = binaryExt.incoming(m2);
                const view2 = new DataView(m3.data.data);

                for (let j = 0; j < view2.byteLength; ++j) {
                    window.assert(view2.getUint8(j) === payload[j]);
                }
                """);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testBinaryExtension(String transport) throws Exception {
        initCometDServer(transport);

        new BinaryService(bayeuxServer);

        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', () => readyLatch.countDown());
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("const binaryLatch = new Latch(1);");
        Latch binaryLatch = javaScript.get("binaryLatch");
        evaluateScript("""
                const buffer = new ArrayBuffer(16);
                const view1 = new DataView(buffer);
                for (let d = 0; d < view1.byteLength; ++d) {
                    view1.setUint8(d, d);
                }
                cometd.addListener('/binary', m => {
                    const view2 = new DataView(m.data.data);
                    for (let i = 0; i < view1.byteLength; ++i) {
                        window.assert(view2.getUint8(i) === view1.getUint8(i));
                    }
                    binaryLatch.countDown();
                });
                cometd.publishBinary('/binary', view1, true);
                """);
        Assertions.assertTrue(binaryLatch.await(5000));
    }

    public static class BinaryService extends AbstractService {
        public BinaryService(BayeuxServer bayeux) {
            super(bayeux, "binary");
            addService("/binary", "binary");
        }

        public void binary(ServerSession session, ServerMessage message) {
            BinaryData data = (BinaryData)message.getData();
            Assertions.assertTrue(data.get("data") instanceof ByteBuffer);
            session.deliver(getServerSession(), message.getChannel(), data, Promise.noop());
        }
    }
}
