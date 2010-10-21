package org.cometd.oort;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;


/* ------------------------------------------------------------ */
/** The Search for Extra Terrestial Intelligence.
 *
 * Well in this case, just the search for a user logged onto an
 * Cometd node in an Oort cluster.
 * <p>
 * Seti allows an application to maintain a mapping from userId to
 * comet client ID using the {@link #associate(String, Client)} and
 * {@link #disassociate(String)} methods. Each cometd node keeps its
 * own associate mapping for clients connected to it.
 * <p>
 * The {@link #sendMessage(Collection, String, Object)} and
 * {@link #sendMessage(String, String, Object)} methods may be
 * used to send a message to user(s) anywhere in the Oort cluster
 * and Seti organizes the search of the distributed associate
 * maps in order to locate the user(s)
 * <p>
 * If users can be directed to shards of cometd servers, then
 * each Seti instance must be told it's shard ID and the {@link #userId2Shard(String)}
 * method must be extended to map users to shards.
 *
 */
public class Seti
{
    public final static String SETI_ATTRIBUTE="org.cometd.oort.Seti";
    public final static String SETI_SHARD="seti.shard";

    final String _setiId;
    final String _shardId;
    final Oort _oort;
    final LocalSession _session;
    final ShardLocation _allShardLocation;
    final ServerChannel _setiIdChannel;
    final ServerChannel _setiAllChannel;
    final ServerChannel _setiShardChannel;

    final ConcurrentMap<String, Location> _uid2Location = new ConcurrentHashMap<String, Location>();

    /* ------------------------------------------------------------ */
    public Seti(Oort oort, String shardId)
    {
        _oort=oort;
        BayeuxServer bayeux = _oort.getBayeux();

        _session = bayeux.newLocalSession("seti");
        _setiId=_oort.getURL().replace("://","_").replace("/","_").replace(":","_");
        _shardId=shardId;

        // TODO proper authorization
        bayeux.getChannel("/set/**").addAuthorizer(GrantAuthorizer.GRANT_ALL);
        
        String channel = "/seti/"+_setiId;
        bayeux.createIfAbsent(channel);
        _setiIdChannel= bayeux.getChannel(channel);
        _setiIdChannel.setPersistent(true);

        channel = "/seti/ALL";
        bayeux.createIfAbsent(channel);
        _setiAllChannel= bayeux.getChannel(channel);
        _setiAllChannel.setPersistent(true);

        channel = "/seti/"+shardId;
        bayeux.createIfAbsent(channel);
        _setiShardChannel= bayeux.getChannel(channel);
        _setiShardChannel.setPersistent(true);

        _allShardLocation = new ShardLocation("ALL");

        try
        {
            _session.handshake();
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }

        _oort.observeChannel(_setiIdChannel.getId());
        _session.getChannel(_setiIdChannel.getId()).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                receive(message);
            }
        });

        _oort.observeChannel(_setiAllChannel.getId());
        _session.getChannel(_setiAllChannel.getId()).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                receive(message);
            }
        });


        _oort.observeChannel(_setiShardChannel.getId());
        _session.getChannel(_setiShardChannel.getId()).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                receive(message);
            }
        });
    }

    /* ------------------------------------------------------------ */
    public void associate(final String userId,final ServerSession session)
    {
        _uid2Location.put(userId,new LocalLocation(session));
        userId2Shard(userId).associate(userId);
    }

    /* ------------------------------------------------------------ */
    public void disassociate(final String userId)
    {
        _uid2Location.remove(userId);
        userId2Shard(userId).disassociate(userId);
    }

    /* ------------------------------------------------------------ */
    public void sendMessage(final String toUser,final String toChannel,final Object data)
    {
        Location location = _uid2Location.get(toUser);
        if (location==null)
            location = userId2Shard(toUser);

        location.sendMessage(toUser,toChannel,data);
    }

    /* ------------------------------------------------------------ */
    public void sendMessage(final Collection<String> toUsers,final String toChannel,final Object message)
    {
        // break toUsers in to shards
        MultiMap shard2users = new MultiMap();
        for (String userId:toUsers)
        {
            ShardLocation shard = userId2Shard(userId);
            shard2users.add(shard,userId);
        }

        // for each shard
        for (Map.Entry<ShardLocation,Object> entry : (Set<Map.Entry<ShardLocation,Object>>)shard2users.entrySet())
        {
            // TODO, we could look at all users in shard to see if we
            // know a setiId for each, and if so, break the user list
            // up into a message for each seti-id. BUT it is probably
            // more efficient just to send to the entire shard (unless
            // the number of nodes in the shard is greater than the
            // number of users).

            ShardLocation shard = entry.getKey();
            Object lazyUsers = entry.getValue();

            if (LazyList.size(lazyUsers)==1)
                shard.sendMessage((String)lazyUsers,toChannel,message);
            else
                shard.sendMessage((List<String>)lazyUsers,toChannel,message);
        }
    }

    /* ------------------------------------------------------------ */
    protected ShardLocation userId2Shard(final String userId)
    {
        return _allShardLocation;
    }

    /* ------------------------------------------------------------ */
    protected void receive(final Message msg)
    {
        if (Log.isDebugEnabled()) Log.debug("SETI "+_oort+":: "+msg);

        if (!(msg.getData() instanceof Map))
            return;

        // extract the message details
        Map<String,Object> data = (Map<String,Object>)msg.getData();
        final String toUid=(String)data.get("to");
        final String fromUid=(String)data.get("from");
        final Object message = data.get("message");
        final String on = (String)data.get("on");

        // Handle any client locations contained in the message
        if (fromUid!=null)
        {
            if (on!=null)
            {
                if (Log.isDebugEnabled()) Log.debug(_oort+":: "+fromUid+" on "+on);
                _uid2Location.put(fromUid,new SetiLocation("/seti/"+on));
            }
            else
            {
                final String off = (String)data.get("off");
                if (off!=null)
                {
                    if (Log.isDebugEnabled()) Log.debug(_oort+":: "+fromUid+" off ");
                    _uid2Location.remove(fromUid,new SetiLocation("/seti/"+off));
                }
            }
        }

        // deliver message
        if (message!=null && toUid!=null)
        {
            final String toChannel=(String)data.get("channel");
            Location location=_uid2Location.get(toUid);

            if (location==null && _setiIdChannel.getId().equals(msg.getChannel()))
                // was sent to this node, so escalate to the shard.
                location =userId2Shard(toUid);

            if (location!=null)
                location.receive(toUid,toChannel,message);
        }

    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private interface Location
    {
        public void sendMessage(String toUser,String toChannel,Object data);
        public void receive(String toUser,String toChannel,Object data);
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class LocalLocation implements Location
    {
        ServerSession _session;

        LocalLocation(ServerSession session)
        {
            _session=session;
        }

        public void sendMessage(String toUser, String toChannel, Object data)
        {
            System.err.println("SETI LocalLocation.send "+toUser+","+toChannel+","+data);
            _session.deliver(_session,toChannel,data,null);
        }

        public void receive(String toUser, String toChannel, Object data)
        {
            System.err.println("SETI LocalLocation.recieve "+toUser+","+toChannel+","+data);
            _session.deliver(_session,toChannel,data,null);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class SetiLocation implements Location
    {
        ClientSessionChannel _channel;

        SetiLocation(String channelId)
        {
            _channel=_session.getChannel(channelId);
        }

        public void sendMessage(String toUser, String toChannel, Object message)
        {
            _channel.publish(new SetiMessage(toUser,toChannel,message));
        }

        public void receive(String toUser, String toChannel, Object message)
        {

        }

        public boolean equals(Object o)
        {
            return o instanceof SetiLocation &&
            ((SetiLocation)o)._channel.equals(_channel);
        }

        public int hashCode()
        {
            return _channel.hashCode();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class ShardLocation implements Location
    {
        ClientSessionChannel _channel;

        ShardLocation(String shardId)
        {
            _channel=_session.getChannel("/seti/"+shardId);
        }

        public void sendMessage(final Collection<String> toUsers, final String toChannel, final Object message)
        {
            _channel.publish(new SetiMessage(toUsers,toChannel,message));
        }

        public void sendMessage(String toUser, String toChannel, Object message)
        {
            _channel.publish(new SetiMessage(toUser,toChannel,message));
        }

        public void receive(String toUser, String toChannel, Object message)
        {

        }

        public void associate(final String user)
        {
            _channel.publish(new SetiPresence(user,true));
        }

        public void disassociate(final String user)
        {
            _channel.publish(new SetiPresence(user,false));
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class SetiMessage implements JSON.Convertible
    {
        String _toUser;
        Collection<String> _toUsers;
        String _toChannel;
        Object _message;

        SetiMessage(String toUser,String toChannel, Object message)
        {
            _toUser=toUser;
            _toChannel=toChannel;
            _message=message;
        }

        SetiMessage(Collection<String> toUsers,String toChannel, Object message)
        {
            _toUsers=toUsers;
            _toChannel=toChannel;
            _message=message;
        }

        public void fromJSON(Map object)
        {
            throw new UnsupportedOperationException();
        }

        public void toJSON(JSON.Output out)
        {
            if (_toUser!=null)
                out.add("to",_toUser);
            else if (_toUsers!=null)
                out.add("to",_toUsers);
            out.add("channel",_toChannel);
            out.add("from",_setiId);
            out.add("message",_message);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class SetiPresence implements JSON.Convertible
    {
        String _user;
        boolean _on;

        SetiPresence(String user,boolean on)
        {
            _user=user;
            _on=on;
        }

        public void fromJSON(Map object)
        {
            throw new UnsupportedOperationException();
        }

        public void toJSON(JSON.Output out)
        {
            out.add("from",_user);
            out.add(_on?"on":"off",_setiId);
        }
    }

    public void disconnect()
    {
        _session.disconnect();
    }

}
