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

import java.util.EventListener;
import java.util.List;
import java.util.Queue;

/* ------------------------------------------------------------ */
/** A Bayeux Client.
 * <p>
 * A Client instance represents a consumer/producer of messages in bayeux. 
 * A client may subscribe to channels and publish messages to channels.
 * <p>
 * Client instances should not be directly created by uses, but should 
 * be obtained via the {@link Bayeux#getClient(String)} or {@link Bayeux#newClient(String, Receiver)}
 * methods.
 * </p>
 * <p>
 * Two types of client may be represented by this interface:<nl>
 * <li>The server representation of a remote client connected via HTTP</li>
 * <li>A server side client</li>
 * </nl>
 */
public interface Client
{
    /* ------------------------------------------------------------ */
    public abstract String getId();
    
    /* ------------------------------------------------------------ */
    public abstract boolean hasMessages();

    /* ------------------------------------------------------------ */
    /** Take any messages queued for a client.
     * @deprecated use listeners
     */
    public abstract List<Message> takeMessages();

    /* ------------------------------------------------------------ */
    public void deliver(Client from, String toChannel, Object data, String id);


    /* ------------------------------------------------------------ */
    /** Add a bayeux client extension.
     * A bayeux client extension may examine a message or return a new message.
     * A bayeux client extension should not modify a message as it may be sent to 
     * multile clients, instead it should clone the passed message.
     * @param ext An extension
     */
    public void addExtension(Extension ext);
    
    
    /* ------------------------------------------------------------ */
    public void addListener(ClientListener listener);
    
    /* ------------------------------------------------------------ */
    public void removeListener(ClientListener listener);
    
    /* ------------------------------------------------------------ */
    /**
     * @return True if the client is local. False if this client is either a remote HTTP client or
     * a java client to a remote server. 
     */
    public boolean isLocal();
    

    /* ------------------------------------------------------------ */
    /** Start a batch of messages.
     * Messages will not be delivered remotely until the corresponding 
     * endBatch is called. Batches may be nested and messages are only sent
     * once all batches are ended.
     */
    public void startBatch();
    
    /* ------------------------------------------------------------ */
    /** End a batch of messages.
     * Messages will not be delivered that have been queued since the previous 
     * startBatch is called. Batches may be nested and messages are only sent
     * once all batches are ended.
     */
    public void endBatch();

    /* ------------------------------------------------------------ */
    /** Disconnect Client.
     * Disconnect client from server.
     */
    public void disconnect();

    /* ------------------------------------------------------------ */
    /**
     * Access the message queue.
     * 
     * @return A queue that is synchronized on the Client instance. 
     */
    public Queue<Message> getQueue();

    /* ------------------------------------------------------------ */
    /** 
    * @param max The size which if a client queue exceeds, forces a call to
    * {@link QueueListener#queueMaxed(Client, Message)} to check if the message should be 
    * added.  If set to -1, there is no queue limit. If set to zero, messages are 
    * not queued.
    */
    public void setMaxQueue(int max);

    /* ------------------------------------------------------------ */
    public int getMaxQueue();
}