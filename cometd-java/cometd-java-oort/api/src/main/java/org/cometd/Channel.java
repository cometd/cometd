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
public interface Channel
{
    /**
     * @return true if the channel has been removed, false if it was not possible to remove the channel
     */
    public boolean remove();

    /**
     * @return the channel's name
     */
    public String getId();

    /**
     * Publishes a message.
     * @param fromClient the client source of the message, or null
     * @param data the message data
     * @param msgId the message ID or null
     */
    public void publish(Client fromClient, Object data, String msgId);

    /**
     * Indicates whether the channel is persistent or not.
     * Non persistent channels are removed when the last subscription is
     * removed.
     * @return true if the Channel will persist even when all subscriptions are gone.
     * @see #setPersistent(boolean)
     */
    public boolean isPersistent();

    /**
     * Sets the persistency of this channel.
     * @param persistent true if the channel is persistent, false otherwise
     */
    public void setPersistent(boolean persistent);

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

    /**
     * Adds the given {@link DataFilter} to this channel.
     * @param filter the data filter to add
     * @see #removeDataFilter(DataFilter)
     */
    public void addDataFilter(DataFilter filter);

    /**
     * Removes the given {@link DataFilter} from this channel.
     * @param filter the data filter to remove
     * @return the removed data filter
     * @see #addDataFilter(DataFilter)
     */
    public DataFilter removeDataFilter(DataFilter filter);

    /**
     * Returns a collection copy of the data filters for this channel.
     * @return the data filters for this channel
     */
    public Collection<DataFilter> getDataFilters();

    /**
     * Adds a channel listener to this channel.
     * @param listener the listener to add
     * @see #removeListener(ChannelListener)
     */
    public void addListener(ChannelListener listener);

    /**
     * Removes the channel listener from this channel.
     * @param listener the listener to remove
     * @see #addListener(ChannelListener)
     */
    public void removeListener(ChannelListener listener);

    /**
     * @return whether the channel is lazy.
     * @see #setLazy(boolean)
     */
    public boolean isLazy();

    /**
     * Sets the lazyness of the channel
     * @param lazy true if channel is lazy
     */
    public void setLazy(boolean lazy);
}
