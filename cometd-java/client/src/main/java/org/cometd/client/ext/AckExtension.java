//========================================================================
// Copyright 2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.client.ext;

import java.util.Map;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;

/**
 * AckExtension
 * 
 * This client-side extension enables the client to acknowledge to the server
 * the messages that the client has received.
 * For the acknowledgement to work, the server must be configured with the
 * correspondent server-side ack extension. If both client and server support
 * the ack extension, then the ack functionality will take place automatically.
 * By enabling this extension, all messages arriving from the server will arrive
 * via the long poll, so the comet communication will be slightly chattier.
 * The fact that all messages will return via long poll means also that the
 * messages will arrive with total order, which is not guaranteed if messages
 * can arrive via both long poll and normal response.
 * Messages are not acknowledged one by one, but instead a group of messages is
 * acknowledged when long poll returns.
 * 
 * @author dyu
 */

public class AckExtension implements Extension
{
    
    public static final String EXT_FIELD = "ack";
    
    private volatile boolean _serverSupportsAcks = false;
    private volatile int _ackId = -1;

    public AckExtension()
    {
        
    }

    public Message send(Client from, Message message)
    {
        return message;
    }
    
    public Message rcv(Client from, Message message)
    {
        return message;
    }

    public Message sendMeta(Client from, Message message)
    {
        if(Bayeux.META_HANDSHAKE.equals(message.getChannel()))
        {
            message.getExt(true).put(EXT_FIELD, Boolean.TRUE);
            _ackId = -1;
        }
        else if(_serverSupportsAcks && Bayeux.META_CONNECT.equals(message.getChannel()))
        {
            message.getExt(true).put(EXT_FIELD, _ackId);
        }
        
        return message;
    }
    
    public Message rcvMeta(Client from, Message message)
    {
        if(Bayeux.META_HANDSHAKE.equals(message.getChannel()))
        {
            Map<String,Object> ext = message.getExt(false);
            _serverSupportsAcks = ext!=null && Boolean.TRUE.equals(ext.get(EXT_FIELD));
        }
        else if(_serverSupportsAcks && Boolean.TRUE.equals(message.get(Bayeux.SUCCESSFUL_FIELD)) 
                && Bayeux.META_CONNECT.equals(message.getChannel()))
        {
            Map<String,Object> ext = message.getExt(false);
            if(ext!=null)
            {
                Object ack = ext.get(EXT_FIELD);
                if(ack instanceof Number)
                    _ackId = ((Number)ack).intValue();
            }
        }
        
        return message;
    }

}
