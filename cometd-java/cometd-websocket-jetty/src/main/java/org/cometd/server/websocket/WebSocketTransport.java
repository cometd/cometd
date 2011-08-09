package org.cometd.server.websocket;

import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.util.log.Log;

/**
 * @deprecated use org.cometd.websocket.server.WebSocketTransport
 */
@Deprecated
public class WebSocketTransport extends org.cometd.websocket.server.WebSocketTransport
{
    {
        Log.warn("Deprecated org.cometd.server.websocket.WebSocketTransport, use org.cometd.websocket.server.WebSocketTransport");
    }

    public WebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux);
    }
}
