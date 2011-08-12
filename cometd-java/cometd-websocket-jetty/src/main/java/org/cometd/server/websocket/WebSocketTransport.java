/*
 * Copyright (c) 2011 the original author or authors.
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
