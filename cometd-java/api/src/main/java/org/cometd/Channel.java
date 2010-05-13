// ========================================================================
// Copyright 2007-2008 Dojo Foundation
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

package org.cometd;

import java.util.Collection;

/**
 * <p>A Bayeux channel.</p>
 * <p>A Channel represents a routing path for messages to {@link Client}s, and
 * looks like a directory path:
 * <pre>
 * /some/channel
 * </pre>
 * Clients may subscribe to a channel and will be delivered all messages
 * published to the channel.</p>
 * <p>Channels may be <em>lazy</em>, which means that all messages published to that channel
 * will be marked as lazy. Lazy messages are queued but do not wake up waiting clients.</p>
 *
 * @version $Revision: 686 $ $Date: 2009-07-03 11:07:24 +0200 (Fri, 03 Jul 2009) $
 */
public interface Channel extends ConfigurableChannel
{
    /**
     * @return true if the channel has been removed, false if it was not possible to remove the channel
     */
    public boolean remove();

    /**
     * Publishes a message.
     * @param fromClient the client source of the message, or null
     * @param data the message data
     * @param msgId the message ID or null
     */
    public void publish(Client fromClient, Object data, String msgId);

    /**
     * Subscribes the given {@link Client} to this channel.
     * @param subscriber the client to subscribe
     * @see #unsubscribe(Client)
     */
    public void subscribe(Client subscriber);

    /**
     * Unsubscribes the given {@link Client} from this channel.
     * @param subscriber the client to unsubscribe
     * @see #subscribe(Client)
     */
    public void unsubscribe(Client subscriber);

    /**
     * Returns a collection that is a copy of clients subscribed to this channel.
     * @return the clients subscribed to this channel
     */
    public Collection<Client> getSubscribers();

    /**
     * @return the number of clients subscribed to this channel
     */
    public int getSubscriberCount();
}
