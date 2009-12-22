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

import org.cometd.bayeux.MetaChannelType;

public interface MetaChannel //extends Channel
{
    void subscribe(MetaMessageListener listener);

    void unsubscribe(MetaMessageListener listener);

    MetaChannelType getType();

    interface Mutable extends MetaChannel
    {
        void notifySubscribers(MetaMessage metaMessage);
    }
}
