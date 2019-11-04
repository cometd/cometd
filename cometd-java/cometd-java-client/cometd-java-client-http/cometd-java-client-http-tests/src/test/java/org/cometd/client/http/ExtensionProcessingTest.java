/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.client.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.cometd.bayeux.BinaryData;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.ext.BinarySessionExtension;
import org.eclipse.jetty.util.IO;
import org.junit.Assert;
import org.junit.Test;

public class ExtensionProcessingTest extends ClientServerTest {
    @Test
    public void testServerExtensionProcessing() throws Exception {
        start(null);
        bayeux.addExtension(new org.cometd.server.ext.BinaryExtension());
        bayeux.addExtension(new GZIPServerExtension());

        final BayeuxClient client = newBayeuxClient();
        client.addExtension(new org.cometd.client.ext.BinaryExtension());
        client.addExtension(new GZIPClientExtension());

        String channelName = "/server_ext_proc";
        final String data = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        testExtensionProcessing(client, channelName, data);
    }

    @Test
    public void testServerSessionExtensionProcessing() throws Exception {
        start(null);
        bayeux.addExtension(new org.cometd.server.ext.BinaryExtension());
        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    from.addExtension(new BinarySessionExtension(bayeux));
                }
                return true;
            }
        });
        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    from.addExtension(new GZIPServerSessionExtension(bayeux));
                }
                return true;
            }
        });
        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    from.addExtension(new ScrambleServerSessionExtension(bayeux));
                }
                return true;
            }
        });

        final BayeuxClient client = newBayeuxClient();
        client.addExtension(new org.cometd.client.ext.BinaryExtension());
        client.addExtension(new GZIPClientExtension());
        client.addExtension(new ScrambleClientExtension());

        String channelName = "/session_ext_proc";
        String data = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        testExtensionProcessing(client, channelName, data);
    }

    private void testExtensionProcessing(final BayeuxClient client, final String channelName, final String data) throws Exception {
        bayeux.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                return data.equals(message.getData());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> client.batch(() -> {
            ClientSessionChannel channel = client.getChannel(channelName);
            channel.subscribe((c, m) -> {
                if (data.equals(m.getData())) {
                    latch.countDown();
                }
            });
            channel.publish(data);
        }));

        Assert.assertTrue(latch.await(555, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    private static void gunzip(Message.Mutable message) throws IOException {
        BinaryData binary = (BinaryData)message.getData();
        GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(binary.asBytes()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IO.copy(gzip, bytes);
        gzip.close();
        message.setData(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    private static byte[] gzip(Message message) throws IOException {
        String data = (String)message.getData();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        gzip.write(data.getBytes(StandardCharsets.UTF_8));
        gzip.close();
        return bytes.toByteArray();
    }

    private static void unscramble(Message.Mutable message) {
        String data = (String)message.getData();
        StringBuilder result = new StringBuilder(data.length());
        for (int i = 0; i < data.length(); ++i) {
            result.append((char)(data.charAt(i) - 1));
        }
        message.setData(result.toString());
    }

    private static String scramble(Message message) {
        String data = (String)message.getData();
        StringBuilder result = new StringBuilder(data.length());
        for (int i = 0; i < data.length(); ++i) {
            result.append((char)(data.charAt(i) + 1));
        }
        return result.toString();
    }

    private static class GZIPClientExtension implements ClientSession.Extension {
        @Override
        public boolean rcv(ClientSession session, Message.Mutable message) {
            try {
                if (!message.isPublishReply()) {
                    gunzip(message);
                }
                return true;
            } catch (IOException x) {
                return false;
            }
        }

        @Override
        public boolean send(ClientSession session, Message.Mutable message) {
            try {
                message.setData(new BinaryData(gzip(message), true, null));
                return true;
            } catch (IOException x) {
                return false;
            }
        }
    }

    private static class ScrambleClientExtension implements ClientSession.Extension {
        @Override
        public boolean rcv(ClientSession session, Message.Mutable message) {
            if (!message.isPublishReply()) {
                unscramble(message);
            }
            return true;
        }

        @Override
        public boolean send(ClientSession session, Message.Mutable message) {
            message.setData(scramble(message));
            return true;
        }
    }

    private static class GZIPServerExtension implements BayeuxServer.Extension {
        @Override
        public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
            try {
                gunzip(message);
                return true;
            } catch (IOException x) {
                return false;
            }
        }

        @Override
        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message) {
            try {
                if (!message.isPublishReply()) {
                    byte[] bytes = gzip(message);
                    message.setData(new BinaryData(bytes, true, null));
                }
                return true;
            } catch (IOException x) {
                return false;
            }
        }
    }

    private static class GZIPServerSessionExtension implements ServerSession.Extension {
        private final BayeuxServer bayeux;

        private GZIPServerSessionExtension(BayeuxServer bayeux) {
            this.bayeux = bayeux;
        }

        @Override
        public boolean rcv(ServerSession session, ServerMessage.Mutable message) {
            try {
                gunzip(message);
                return true;
            } catch (IOException x) {
                return false;
            }
        }

        @Override
        public ServerMessage send(ServerSession sender, ServerSession session, ServerMessage message) {
            try {
                if (message.isPublishReply()) {
                    return message;
                } else {
                    ServerMessage.Mutable result = bayeux.newMessage();
                    result.putAll(message);
                    result.setData(new BinaryData(gzip(message), true, null));
                    return result;
                }
            } catch (IOException x) {
                return null;
            }
        }
    }

    private static class ScrambleServerSessionExtension implements ServerSession.Extension {
        private final BayeuxServer bayeux;

        private ScrambleServerSessionExtension(BayeuxServer bayeux) {
            this.bayeux = bayeux;
        }

        @Override
        public boolean rcv(ServerSession session, ServerMessage.Mutable message) {
            unscramble(message);
            return true;
        }

        @Override
        public ServerMessage send(ServerSession sender, ServerSession session, ServerMessage message) {
            if (message.isPublishReply()) {
                return message;
            } else {
                ServerMessage.Mutable result = bayeux.newMessage();
                result.putAll(message);
                result.setData(scramble(message));
                return result;
            }
        }
    }
}
