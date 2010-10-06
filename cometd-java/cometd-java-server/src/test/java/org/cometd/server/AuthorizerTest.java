package org.cometd.server;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.Authorizer.Operation;
import org.cometd.server.authorizer.ChannelAuthorizer;
import org.cometd.server.authorizer.ChannelsAuthorizer;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;


public class AuthorizerTest
{
    final BayeuxServerImpl _bayeux = new BayeuxServerImpl();

    @Test
    public void testChannelAuthorizer()
    {
        ChannelAuthorizer auth = new ChannelAuthorizer(EnumSet.noneOf(Operation.class));
        
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(false,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/foo/bar");
        
        auth = new ChannelAuthorizer(EnumSet.noneOf(Operation.class),"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(false,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/foo/bar");
        
        auth = new ChannelAuthorizer(EnumSet.noneOf(Operation.class),"/foo/*");
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(false,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/foo/bar");

        
        auth = new ChannelAuthorizer(EnumSet.of(Operation.CREATE),"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(true,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(false,auth,Operation.CREATE,"/other/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/other/bar");
        
        auth = new ChannelAuthorizer(EnumSet.of(Operation.CREATE,Operation.PUBLISH),"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(true,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(true,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(false,auth,Operation.CREATE,"/other/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/other/bar");

        auth = new ChannelAuthorizer(EnumSet.of(Operation.CREATE,Operation.PUBLISH),"/foo/*");
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(true,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(true,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(false,auth,Operation.CREATE,"/other/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/other/bar");
        

        auth = new ChannelAuthorizer(EnumSet.of(Operation.CREATE,Operation.PUBLISH),"/**");
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(true,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(true,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(true,auth,Operation.CREATE,"/other/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(true,auth,Operation.PUBLISH,"/other/bar");
        
    }
    
    @Test
    public void testChannelsAuthorizer()
    {
        ChannelsAuthorizer auth = new ChannelsAuthorizer();
        
        assertGranted(false,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.CREATE,"/*");
        assertGranted(false,auth,Operation.CREATE,"/**");
        
        ChannelAuthorizer auth0 = new ChannelAuthorizer(EnumSet.of(Operation.CREATE,Operation.PUBLISH),"/foo/bar");
        auth.addChannelAuthorizer(auth0);
        
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(true,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(true,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(false,auth,Operation.CREATE,"/other/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/other/bar");
        

        ChannelAuthorizer auth1 = new ChannelAuthorizer(EnumSet.of(Operation.SUBSCRIBE),"/other/bar");
        auth.addChannelAuthorizer(auth1);
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(true,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(true,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(false,auth,Operation.CREATE,"/other/bar");
        assertGranted(true,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/other/bar");
        

        auth.removeChannelAuthorizer(auth0);
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(false,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(false,auth,Operation.CREATE,"/other/bar");
        assertGranted(true,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/other/bar");

        auth.addChannelAuthorizer(auth0);
        ChannelAuthorizer auth2 = new ChannelAuthorizer(EnumSet.of(Operation.SUBSCRIBE),"/foo/*");
        auth.addChannelAuthorizer(auth2);
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(true,auth,Operation.CREATE,"/foo/bar");
        assertGranted(true,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(true,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(false,auth,Operation.CREATE,"/other/bar");
        assertGranted(true,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/other/bar");
        
        ChannelAuthorizer auth3 = new ChannelAuthorizer(EnumSet.of(Operation.SUBSCRIBE),"/foo/*");
        auth.addChannelAuthorizer(auth3);
        auth.removeChannelAuthorizer(auth3);
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(true,auth,Operation.CREATE,"/foo/bar");
        assertGranted(true,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(true,auth,Operation.PUBLISH,"/foo/bar");
        assertGranted(false,auth,Operation.HANDSHAKE,"/other/bar");
        assertGranted(false,auth,Operation.CREATE,"/other/bar");
        assertGranted(true,auth,Operation.SUBSCRIBE,"/other/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/other/bar");
       

        auth.removeChannelAuthorizer(auth0);
        auth.removeChannelAuthorizer(auth1);
        auth.removeChannelAuthorizer(auth2);
        auth.removeChannelAuthorizer(auth3);
        assertGranted(false,auth,Operation.HANDSHAKE,"/foo/bar");
        assertGranted(false,auth,Operation.CREATE,"/foo/bar");
        assertGranted(false,auth,Operation.SUBSCRIBE,"/foo/bar");
        assertGranted(false,auth,Operation.PUBLISH,"/foo/bar");
        
    }
    
    
    protected void assertGranted(boolean granted, Authorizer authorizer, Authorizer.Operation op, String channel)
    {
        final AtomicBoolean is_granted = new AtomicBoolean();
        final AtomicBoolean is_denied = new AtomicBoolean();
        Authorizer.Permission permission = new  Authorizer.Permission()
        {
            public void granted()
            {
                is_granted.set(true);
            }

            public void denied()
            {
                is_denied.set(true);
            }

            public void denied(String reason)
            {
                denied();
            }
        };
        
        ServerMessageImpl message = new ServerMessageImpl();
        ChannelId id = new ChannelId(channel);
        message.asMutable().setChannel(channel);
        ServerChannelImpl channelImpl = new ServerChannelImpl(_bayeux,id);
        
        switch(op)
        {
            case CREATE:
                authorizer.canCreate(permission,_bayeux,null,id,message);
                break;
            case SUBSCRIBE:
                authorizer.canSubscribe(permission,_bayeux,null,channelImpl,message);
                break;
            case PUBLISH:
                authorizer.canPublish(permission,_bayeux,null,channelImpl,message);
                break;
            case HANDSHAKE:
                authorizer.canHandshake(permission,_bayeux,null,message);
                break;
        }

        if (granted)
            assertTrue(is_granted.get());
        else 
            assertFalse(is_granted.get());
        
        assertFalse(is_denied.get());
        
    }
    

}
