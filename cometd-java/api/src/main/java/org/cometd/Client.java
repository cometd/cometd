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
//========================================================================

package org.cometd;

import java.util.List;
import java.util.Queue;

/* ------------------------------------------------------------ */
/**
 * <p>A Bayeux Client.</p>
 * <p>A Client instance represents a consumer/producer of messages in bayeux.
 * A Client may subscribe to channels and publish messages to channels.</p>
 * <p>Client instances should not be directly created by uses, but should
 * be obtained via the {@link Bayeux#getClient(String)} or {@link Bayeux#newClient(String)}
 * methods.</p>
 * <p>Two types of client may be represented by this interface:</p>
 * <ul>
 * <li>The server representation of a remote client connected via HTTP</li>
 * <li>A remote client</li>
 * </ul>
 *
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public interface Client
{
    /* ------------------------------------------------------------ */
    /**
     * @return the unique ID representing this client
     */
    public abstract String getId();

    /* ------------------------------------------------------------ */
    /**
     * @return whether or not this client has messages to send
     */
    public abstract boolean hasMessages();

    /* ------------------------------------------------------------ */
    /**
     * Takes any messages queued for a client.
     *
     * @deprecated use {@link #addListener(ClientListener)} to be notified of messages
     */
    public abstract List<Message> takeMessages();

    /* ------------------------------------------------------------ */
    /**
     * Delivers a message to the remote client represented by this object.
     *
     * @param from      the Client that sends the message
     * @param toChannel the channel onto which the message is sent
     * @param data      the data of the message
     * @param id        the message ID
     */
    public void deliver(Client from, String toChannel, Object data, String id);

    /* ------------------------------------------------------------ */
    /**
     * Adds a bayeux client extension.
     * A bayeux client extension may examine a message or return a new message.
     * A bayeux client extension should not modify a message as it may be sent to
     * multiple clients, instead it should deep copy the passed message.
     *
     * @param ext the extension to add
     * @see #removeExtension(Extension)
     */
    public void addExtension(Extension ext);

    /**
     * Removes a bayeux client extension.
     *
     * @param ext the extension to removeù
     * @see #addExtension(Extension)
     */
    public void removeExtension(Extension ext);

    /* ------------------------------------------------------------ */
    /**
     * Adds a listener.
     *
     * @param listener the listener to add
     * @see #removeListener(ClientListener)
     */
    public void addListener(ClientListener listener);

    /* ------------------------------------------------------------ */
    /**
     * Removes a listener
     *
     * @param listener the listener to remove
     * @see #addListener(ClientListener)
     */
    public void removeListener(ClientListener listener);

    /* ------------------------------------------------------------ */
    /**
     * @return true if the client is local, false if this client is either
     *         a remote HTTP client or a java client to a remote server.
     */
    public boolean isLocal();

    /* ------------------------------------------------------------ */
    /**
     * Starts a batch of messages.
     * Messages will not be delivered remotely until the corresponding
     * {@link #endBatch()} is called.
     * Batches may be nested and messages are only sent once all batches are ended.
     *
     * @see #endBatch()
     */
    public void startBatch();

    /* ------------------------------------------------------------ */
    /**
     * Ends a batch of messages.
     * Messages will not be delivered that have been queued since the previous
     * {@link #startBatch()} is called.
     * Batches may be nested and messages are only sent once all batches are ended.
     *
     * @see #startBatch()
     */
    public void endBatch();

    /* ------------------------------------------------------------ */
    /**
     * Disconnects this Client from the server.
     */
    public void disconnect();

    /* ------------------------------------------------------------ */
    /**
     * @return the message queue (its usage must synchronize on this Client instance).
     */
    public Queue<Message> getQueue();

    /* ------------------------------------------------------------ */
    /**
     * @param max The size which if a client queue exceeds, forces a call to
     *            {@link QueueListener#queueMaxed(Client, Client, Message)} to check if the message should be added.
     *            If set to -1, there is no queue limit. If set to zero, messages are not queued.
     * @see #getMaxQueue()
     */
    public void setMaxQueue(int max);

    /* ------------------------------------------------------------ */
    /**
     * @return the max queue size
     * @see #setMaxQueue(int)
     */
    public int getMaxQueue();
}
