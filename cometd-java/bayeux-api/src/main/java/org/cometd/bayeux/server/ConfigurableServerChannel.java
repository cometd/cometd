// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================


package org.cometd.bayeux.server;

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
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
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
     */
    void addListener(ServerChannelListener listener);

    /**
     * @param listener the listener to remove
     */
    void removeListener(ServerChannelListener listener);

    /**
     * @return whether the channel is lazy
     */
    boolean isLazy();

    /**
     * A lazy channel marks all messages published to it as lazy.
     * @param lazy whether the channel is lazy
     */
    void setLazy(boolean lazy);

    /**
     * @return whether the channel is persistent
     */
    boolean isPersistent();

    /**
     * A persistent channel is not removed when the last subscription is removed
     * @param persistent whether the channel is persistent
     */
    void setPersistent(boolean persistent);
}
