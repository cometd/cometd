// ========================================================================
// Copyright (c) 2009-2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================


package org.cometd.bayeux;

import java.util.HashMap;
import java.util.Map;



/* ------------------------------------------------------------ */
/** @deprecated REVIEW - is this needed?
 */
public enum MetaChannelId
{
    HANDSHAKE(Channel.META_HANDSHAKE),
    CONNECT(Channel.META_CONNECT),
    SUBSCRIBE(Channel.META_SUBSCRIBE),
    UNSUBSCRIBE(Channel.META_UNSUBSCRIBE),
    DISCONNECT(Channel.META_DISCONNECT);

    private final String _channelId;

    private MetaChannelId(String channelId)
    {
        _channelId = channelId;
        MetaChannelIds.values.put(channelId, this);
    }

    public String getChannelId()
    {
        return _channelId;
    }

    public static MetaChannelId from(String channelId)
    {
        return MetaChannelIds.values.get(channelId);
    }

    private static class MetaChannelIds
    {
        private static Map<String, MetaChannelId> values = new HashMap<String, MetaChannelId>();
    }
}