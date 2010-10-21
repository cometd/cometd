package org.cometd.server;


import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class AuthorizerTest
{
    @Test
    public void testAuthorizer()
    {
        final BayeuxServerImpl bayeux = new BayeuxServerImpl();
        
        bayeux.createIfAbsent("/foo/bar");
        
        // TODO
        
    }
}
