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


/* ------------------------------------------------------------ */
/** Configurable Server Channel.
 * 
 * This interface represents the methods available
 * on a ServerChannel that can be used to configure it.
 * This API is available while a new channel is being 
 * initialized while being added to the {@link BayeuxServer} instance.
 * <p>
 * This API may be called via a {@link BayeuxServer.ChannelListener#configureChannel(ConfigurableServerChannel)} 
 * so that a channel can be setup before any publish or subscribes
 * are performed on it. 
 */
public interface ConfigurableServerChannel extends Channel
{
    
    /* ------------------------------------------------------------ */
    /** Channel Initializer.
     * <p>Interface to atomically set the initial configuration of 
     * a channel. 
     */
    public interface Initializer
    {
        void configureChannel(ConfigurableServerChannel channel);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void addListener(ServerChannelListener listener);

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void removeListener(ServerChannelListener listener);
    
    /* ------------------------------------------------------------ */
    /** Set lazy channel
     * @param lazy If true, all messages published to this channel will
     * be marked as lazy.
     */
    void setLazy(boolean lazy);
    
    /* ------------------------------------------------------------ */
    /** Set persistent channel
     * @param persistent If true, the channel will not be removed when 
     * the last subscription is removed.
     */
    void setPersistent(boolean persistent);
    
    
}