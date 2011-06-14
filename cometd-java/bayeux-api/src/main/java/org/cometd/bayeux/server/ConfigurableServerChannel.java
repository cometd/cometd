/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.bayeux.server;

import java.util.List;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerChannel.ServerChannelListener;

/**
 * <p>A {@link ConfigurableServerChannel} offers an API that can be used to
 * configure {@link ServerChannel}s at creation time.</p>
 * <p>{@link ServerChannel}s may be created concurrently via
 * {@link BayeuxServer#createIfAbsent(String, Initializer...)} and it is
 * important that the creation of a channel is atomic so that its
 * configuration is executed only once, and so that it is guaranteed that
 * it happens before any message can be published or received by the channel.</p>
 */
public interface ConfigurableServerChannel extends Channel
{
    /**
     * A listener interface by means of which listeners can atomically
     * set the initial configuration of a channel.
     */
    public interface Initializer
    {
        /**
         * Callback invoked when a channel is created and needs to be configured
         * @param channel the channel to configure
         */
        void configureChannel(ConfigurableServerChannel channel);
    }

    /**
     * @param listener the listener to add
     * @see #removeListener(ServerChannelListener)
     */
    void addListener(ServerChannelListener listener);

    /**
     * @param listener the listener to remove
     * @see #addListener(ServerChannelListener)
     */
    void removeListener(ServerChannelListener listener);

    /**
     * @return an immutable list of listeners
     * @see #addListener(ServerChannelListener)
     */
    List<ServerChannelListener> getListeners();

    /**
     * @return whether the channel is lazy
     * @see #setLazy(boolean)
     */
    boolean isLazy();

    /**
     * A lazy channel marks all messages published to it as lazy.
     * @param lazy whether the channel is lazy
     * @see #isLazy()
     */
    void setLazy(boolean lazy);

    /**
     * @return whether the channel is persistent
     * @see #setPersistent(boolean)
     */
    boolean isPersistent();

    /**
     * A persistent channel is not removed when the last subscription is removed
     * @param persistent whether the channel is persistent
     * @see #isPersistent()
     */
    void setPersistent(boolean persistent);

    /**
     * <p>Adds the given {@link Authorizer} that grants or denies operations on this channel.</p>
     * <p>Operations must be granted by at least one Authorizer and must not be denied by any.</p>
     *
     * @param authorizer the Authorizer to add
     * @see #removeAuthorizer(Authorizer)
     * @see Authorizer
     */
    public void addAuthorizer(Authorizer authorizer);

    /**
     * <p>Removes the given {@link Authorizer}.</p>
     * @param authorizer the Authorizer to remove
     * @see #addAuthorizer(Authorizer)
     */
    public void removeAuthorizer(Authorizer authorizer);

    /**
     * @return an immutable list of authorizers for this channel
     */
    public List<Authorizer> getAuthorizers();
}
