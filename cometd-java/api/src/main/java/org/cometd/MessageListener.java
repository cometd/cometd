// ========================================================================
// Copyright 2007 Dojo Foundation
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
// ========================================================================

package org.cometd;


/**
 * Message Listener Interface.
 *
 * Objects implementing this interface may listen for message deliverly events
 * by calling the {@link Client#addListener(ClientListener)}  The {@link Synchronous}
 * or {@link Asynchronous} nested interfaces may be used as a mixin to specify the style
 * of delivery required.  If neither subtype is specified, then the {@link Bayeux}
 * implementation may use either method.
 *
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public interface MessageListener extends ClientListener
{
    /**
     * Called when a message is delivered to the client
     * @param fromClient the client that sent the message
     * @param toClient the client that received the message
     * @param msg the message
     */
    public void deliver(Client fromClient, Client toClient, Message msg);

    /**
     * Subtype of MessageListener that requires synchronous message delivery.
     * The {@link Client} object is locked during the call to
     * {@link MessageListener#deliver(Client, Client, Message)}, thus
     * guaranteeing that only a single message will be delivered at once and
     * in order of receipt.
     */
    public interface Synchronous extends MessageListener {}

    /**
     * Subtype of MesssageListener that requires asynchronous message delivery.
     * Calls to {@link MessageListener#deliver(Client, Client, Message)} may occur
     * in parallel and possibly out of order.
     */
    public interface Asynchronous extends MessageListener {}
}
