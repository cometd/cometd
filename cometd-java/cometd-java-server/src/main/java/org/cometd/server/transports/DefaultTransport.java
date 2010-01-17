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


package org.cometd.server.transports;

import org.cometd.common.AbstractTransport;

public class DefaultTransport extends AbstractTransport
{
    public final static String TIMEOUT_OPTION="timeout";
    public final static String INTERVAL_OPTION="interval";
    public final static String MAX_INTERVAL_OPTION="maxInterval";
    public final static String MAX_LAZY_OPTION="maxLazy";
    public final static String TRANSPORT_PARAMETER_OPTION="transportParameter";
    
    public DefaultTransport()
    {
        super("*");
        _options.put(TIMEOUT_OPTION,10000);
        _options.put(INTERVAL_OPTION,0);
        _options.put(MAX_INTERVAL_OPTION,10000);
        _options.put(MAX_LAZY_OPTION,2000);
        
        _options.put(TRANSPORT_PARAMETER_OPTION,"transport");
    }
    
}