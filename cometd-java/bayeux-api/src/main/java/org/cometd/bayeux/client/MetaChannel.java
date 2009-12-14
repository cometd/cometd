// ========================================================================
// Copyright (c) 2009-2010 Mort Bay Consulting Pty. Ltd.
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


package org.cometd.bayeux.client;

import java.util.EventListener;

import org.cometd.bayeux.Channel;

public interface MetaChannel extends Channel
{
    Channel.MetaType getMetaType();

    void addListener(Listener listener);
    
    interface Listener extends EventListener
    {}
}