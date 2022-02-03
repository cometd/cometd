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
package org.cometd.client.transport;

/**
 * <p>Classes implementing {@link MessageClientTransport} indicate that the transport they provide
 * receives messages without the need of a previous request, like it happens in HTTP long poll.</p>
 * <p>Websocket or SPDY allow unsolicited messages to arrive to the client, and this must be
 * processed by a {@link TransportListener} that has not been passed during a request, but has
 * instead been provided at startup by {@link #setMessageTransportListener(TransportListener)}.</p>
 */
public interface MessageClientTransport {
    /**
     * @param listener the listener that handles unsolicited messages from the server
     */
    public void setMessageTransportListener(TransportListener listener);
}
