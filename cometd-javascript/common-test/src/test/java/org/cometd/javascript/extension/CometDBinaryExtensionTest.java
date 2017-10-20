/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CometDBinaryExtensionTest extends AbstractCometDTransportsTest {
    @Before
    public void initExtension() throws Exception {
        bayeuxServer.addExtension(new BinaryExtension());
        provideBinaryExtension();
    }

    @Test
    public void testZ85Basic() throws Exception {
        evaluateScript("" +
                "var binaryExt = cometd.getExtension('binary');" +
                "" +
                "var payload = 'HelloWorld';" +
                "" +
                "var m1 = {" +
                "    channel: '/binary'," +
                "    ext: {" +
                "        binary: {}" +
                "    }," +
                "    data: {" +
                "        data: payload," +
                "        last: true" +
                "    }" +
                "};" +
                "" +
                "var m2 = binaryExt.incoming(m1);" +
                "" +
                "window.assert(m2.data.data instanceof ArrayBuffer);" +
                "var control = [134, 79, 210, 111, 181, 89, 247, 91];" +
                "var view = new DataView(m2.data.data);" +
                "for (var i = 0; i < control.length; ++i) {" +
                "    window.assert(view.getUint8(i) === control[i]);" +
                "}" +
                "" +
                "var m3 = binaryExt.outgoing(m2);" +
                "" +
                "window.assert(m3.data.data === payload);");
    }

    @Test
    public void testZ85Unpadded() throws Exception {
        evaluateScript("" +
                "var binaryExt = cometd.getExtension('binary');" +
                "" +
                "var payload = [0xC, 0x0, 0xF, 0xF, 0xE, 0xE];" +
                "var buffer = new ArrayBuffer(payload.length);" +
                "var view1 = new DataView(buffer);" +
                "for (var i = 0; i < payload.length; ++i) {" +
                "    view1.setUint8(i, payload[i]);" +
                "}" +
                "" +
                "var m1 = {" +
                "    channel: '/binary'," +
                "    ext: {" +
                "        binary: {}" +
                "    }," +
                "    data: {" +
                "        data: buffer," +
                "        last: true" +
                "    }" +
                "};" +
                "" +
                "var m2 = binaryExt.outgoing(m1);" +
                "var m3 = binaryExt.incoming(m2);" +
                "" +
                "var view2 = new DataView(m3.data.data);" +
                "for (var j = 0; j < view2.byteLength; ++j) {" +
                "    window.assert(view2.getUint8(j) === payload[j]);" +
                "}");
    }

    @Test
    public void testBinaryExtension() throws Exception {
        new BinaryService(bayeuxServer);

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        evaluateScript("var binaryLatch = new Latch(1);");
        Latch binaryLatch = javaScript.get("binaryLatch");
        evaluateScript("" +
                "var buffer = new ArrayBuffer(16);" +
                "var view1 = new DataView(buffer);" +
                "for (var d = 0; d < view1.byteLength; ++d) {" +
                "    view1.setUint8(d, d);" +
                "}" +
                "cometd.addListener('/binary', function(m) {" +
                "    var view2 = new DataView(m.data.data);" +
                "    for (var i = 0; i < view1.byteLength; ++i) {" +
                "        window.assert(view2.getUint8(i) === view1.getUint8(i));" +
                "    }" +
                "    binaryLatch.countDown();" +
                "});" +
                "cometd.publishBinary('/binary', view1, true);" +
                "");
        Assert.assertTrue(binaryLatch.await(5000));
    }

    public static class BinaryService extends AbstractService {
        public BinaryService(BayeuxServer bayeux) {
            super(bayeux, "binary");
            addService("/binary", "binary");
        }

        public void binary(ServerSession session, ServerMessage message) {
            BinaryData data = (BinaryData)message.getData();
            Assert.assertTrue(data.get("data") instanceof ByteBuffer);
            session.deliver(getServerSession(), message.getChannel(), data, Promise.noop());
        }
    }
}
