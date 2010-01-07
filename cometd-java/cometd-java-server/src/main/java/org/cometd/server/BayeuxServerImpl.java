package org.cometd.server;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.ChannelId;

public class BayeuxServerImpl implements BayeuxServer
{
    private final SecureRandom _random = new SecureRandom();
    private final List<BayeuxServerListener> _listeners = new CopyOnWriteArrayList<BayeuxServerListener>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final ServerMessagePool _pool = new ServerMessagePool();
    private final ServerChannelImpl _root=new ServerChannelImpl(this,null,new ChannelId("/"));
    private final ConcurrentMap<String, ServerSessionImpl> _sessions = new ConcurrentHashMap<String, ServerSessionImpl>();
    private final ConcurrentMap<String, ServerChannelImpl> _channels = new ConcurrentHashMap<String, ServerChannelImpl>();
    private SecurityPolicy _policy;

    /* ------------------------------------------------------------ */
    int randomInt()
    {
        return _random.nextInt();
    }

    /* ------------------------------------------------------------ */
    int randomInt(int n)
    {
        return _random.nextInt(n);
    }

    /* ------------------------------------------------------------ */
    long randomLong()
    {
        return _random.nextLong();
    }

    /* ------------------------------------------------------------ */
    ServerChannelImpl root()
    {
        return _root;
    }

    /* ------------------------------------------------------------ */
    public Object getCurrentTransport()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    public SecurityPolicy getSecurityPolicy()
    {
        return _policy;
    }

    /* ------------------------------------------------------------ */
    public ServerChannel getServerChannel(String channelId, boolean create)
    {
        ServerChannelImpl channel = _channels.get(channelId);
        if (channel==null && create)
            channel=_root.getChild(new ChannelId(channelId),true);
        return channel;
    }

    /* ------------------------------------------------------------ */
    public ServerSession getServerSession(String clientId)
    {
        return _sessions.get(clientId);
    }

    /* ------------------------------------------------------------ */
    protected void addServerSession(ServerSessionImpl session)
    {
        _sessions.put(session.getId(),session);
        for (BayeuxServerListener listener : _listeners)
        {
            if (listener instanceof BayeuxServer.SessionListener)
                ((SessionListener)listener).sessionAdded(session);
        }
    }

    /* ------------------------------------------------------------ */
    protected void removeServerSession(ServerSessionImpl session,boolean timedout)
    {
        if(_sessions.remove(session.getId())==session)
        {
            for (BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.SessionListener)
                    ((SessionListener)listener).sessionRemoved(session,timedout);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public LocalSession newLocalSession(String idHint)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    public ServerMessage.Mutable newServerMessage()
    {
        return _pool.getServerMessage();
    }

    /* ------------------------------------------------------------ */
    public void setSecurityPolicy(SecurityPolicy securityPolicy)
    {
        _policy=securityPolicy;
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
    }

    /* ------------------------------------------------------------ */
    public void addListener(BayeuxListener listener)
    {
        if (!(listener instanceof BayeuxServerListener))
            throw new IllegalArgumentException("!BayeuxServerListener");
        _listeners.add((BayeuxServerListener)listener);
    }

    /* ------------------------------------------------------------ */
    public Channel getChannel(String channelId)
    {
        return _channels.get(channelId);
    }

    /* ------------------------------------------------------------ */
    public void removeListener(BayeuxListener listener)
    {
        _listeners.remove(listener);
    }

    /* ------------------------------------------------------------ */
    protected boolean extendRecv(ServerSession from, Message.Mutable message)
    {
        for (Extension ext: _extensions)
            if (!ext.rcv(from,message))
                return false;
        return true;
    }

    /* ------------------------------------------------------------ */
    protected boolean extendRecvMeta(ServerSession from, Message.Mutable message)
    {
        for (Extension ext: _extensions)
            if (!ext.rcv(from,message))
                return false;
        return true;
    }

    /* ------------------------------------------------------------ */
    protected boolean extendSend(ServerSession from, Message.Mutable message)
    {
        for (Extension ext: _extensions)
            if (!ext.rcv(from,message))
                return false;
        return true;
    }

    /* ------------------------------------------------------------ */
    protected boolean extendSendMeta(ServerSession from, Message.Mutable message)
    {
        for (Extension ext: _extensions)
            if (!ext.rcv(from,message))
                return false;
        return true;
    }

    /* ------------------------------------------------------------ */
    void addServerChannel(ServerChannelImpl channel)
    {
        ServerChannelImpl old = _channels.putIfAbsent(channel.getId(),channel);
        if (old!=null)
            throw new IllegalStateException();
        for (BayeuxServerListener listener : _listeners)
        {
            if (listener instanceof BayeuxServer.ChannelListener)
            ((ChannelListener)listener).channelAdded(channel);
        }
    }


}
