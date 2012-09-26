/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.server.ext;

import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.ServerSessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Installing this extension in a {@link BayeuxServer} provides support to
 * message acknowledgement if a client also supports it.</p>
 * <p/>
 * <p>The main role of this extension is to install the
 * {@link AcknowledgedMessagesClientExtension} on the {@link ServerSession} instances
 * created during handshake for clients that also support the ack extension.</p>
 */
public class AcknowledgedMessagesExtension extends Extension.Adapter
{
    private final Logger _logger = LoggerFactory.getLogger(getClass().getName());
    private final Map<String, Object> _replyExt;

    public AcknowledgedMessagesExtension()
    {
        _replyExt = new HashMap<>(1);
        _replyExt.put("ack", true);
    }

    @Override
    public boolean sendMeta(ServerSession to, Mutable message)
    {
        String channel = message.getChannel();
        if (channel == null)
            return true;

        if (Channel.META_HANDSHAKE.equals(channel) && Boolean.TRUE.equals(message.get(Message.SUCCESSFUL_FIELD)))
        {
            Message rcv = message.getAssociated();

            Map<String, Object> ext = rcv.getExt();
            boolean clientRequestedAcks = ext != null && ext.get("ack") == Boolean.TRUE;

            if (clientRequestedAcks && to != null)
            {
                _logger.info("Enabled message acknowledgement for client {}", to);
                to.addExtension(new AcknowledgedMessagesClientExtension(to));
                ((ServerSessionImpl)to).setMetaConnectDeliveryOnly(true);
            }

            Map<String, Object> mext = message.getExt();
            if (mext != null)
                mext.put("ack", Boolean.TRUE);
            else
                message.put(Message.EXT_FIELD, _replyExt);
        }

        return true;
    }
}
