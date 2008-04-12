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

package dojox.cometd;

import java.util.EventListener;
import java.util.List;

/* ------------------------------------------------------------ */
/** A Bayeux Client.
 * <p>
 * A client may subscribe to channels and publish messages to channels.
 * Client instances should not be directly created by uses, but should 
 * be obtained via the {@link Bayeux#getClient(String)} or {@link Bayeux#newClient(String, Receiver)}
 * methods.
 * </p>
 * <p>
 * Three types of client may be represented by this interface:<nl>
 * <li>The server representation of a remote client connected via HTTP</li>
 * <li>A server side client</li>
 * <li>A java client connected to a remote Bayeux server</li>
 * </nl>
 */
public interface Client
{
    /* ------------------------------------------------------------ */
    public abstract String getId();

    /* ------------------------------------------------------------ */
    /** Publish data from this client.
     * This is equivalent to {@link Bayeux#publish(Client, String, Object, String)} with this client passed
     * as the fromClient.
     * @deprecated use {@link Channel#publish(Client, Object, String)}
     * @param data The data itself which must be an Object that can be encoded with {@link JSON}.
     * @param toChannel The Channel ID to which the data is targetted
     * @param msgId optional message ID or null for automatic generation of a message ID.
     */
    public void publish(String toChannel, Object data, String msgId);

    /* ------------------------------------------------------------ */
    /** Subscribe this client to a channel.
     * This is equivalent to {@link Bayeux#subscribe(String, Client)} with this client passed.
     * Equivalent to getChannel(toChannel).subscribe(subscriber).
     * @deprecated use {@link Channel#subscribe(Client)}
     * @param toChannel
     */
    public void subscribe(String toChannel);

    /* ------------------------------------------------------------ */
    /** Unsubscribe this client from a channel.
     * This is equivalent to {@link Bayeux#unsubscribe(String, Client)} with this client passed.
     * @deprecated use {@link Channel#unsubscribe(Client)}
     * @param toChannel
     */
    public void unsubscribe(String toChannel);
    
    /* ------------------------------------------------------------ */
    public abstract boolean hasMessages();

    /* ------------------------------------------------------------ */
    /** Take any messages queued for a client.
     * 
     */
    public abstract List<Message> takeMessages();

    /* ------------------------------------------------------------ */
    /** Deliver a message to this client only
     * Deliver a message directly to the client. The message is not 
     * filtered or published to a channel.
     * @deprecated use {@link #deliver(Client, String, Object, String)}
     * @param from The Client that published the message, or null if not known/available
     * @param message
     */
    public void deliver(Client from, Message message);

    /* ------------------------------------------------------------ */
    public void deliver(Client from, String toChannel, Object data, String id);

    /* ------------------------------------------------------------ */
    /** 
     * @deprecated use {@link #addListener(EventListener)}
     */
    public void setListener(Listener listener);

    /* ------------------------------------------------------------ */
    /** 
     * @deprecated Returns only the first listener added
     */
    public Listener getListener();

    /* ------------------------------------------------------------ */
    public void addListener(EventListener listener);
    
    /* ------------------------------------------------------------ */
    public void removeListener(EventListener listener);
    
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
    
    
}