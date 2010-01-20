package org.cometd.oort;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.Channel;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
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
public class Seti extends AbstractLifeCycle
{
    public final static String SETI_ATTRIBUTE="org.cometd.oort.Seti";
    public final static String SETI_SHARD="seti.shard";
    
    final String _setiId;
    final String _setiChannelId;
    final String _shardId;
    final Oort _oort;
    final Client _client;
    final ShardLocation _allShardLocation;
    final Channel _setiIdChannel;
    final Channel _setiAllChannel;
    final Channel _setiShardChannel;
    
    final ConcurrentMap<String, Location> _uid2Location = new ConcurrentHashMap<String, Location>();
    
    /* ------------------------------------------------------------ */
    public Seti(Oort oort, String shardId)
    {
        _oort=oort;
        _client = _oort.getBayeux().newClient("seti");
        _setiId=_oort.getURL().replace("://","_").replace("/","_").replace(":","_");
        _shardId=shardId;

        _setiChannelId="/seti/"+_setiId;
        _setiIdChannel=_oort.getBayeux().getChannel(_setiChannelId,true);
        _setiIdChannel.setPersistent(true);
        _oort.observeChannel(_setiIdChannel.getId());
        _setiIdChannel.subscribe(_client);
        
        _setiAllChannel=_oort.getBayeux().getChannel("/seti/ALL",true);
        _setiAllChannel.setPersistent(true);
        _oort.observeChannel(_setiAllChannel.getId());
        _setiAllChannel.subscribe(_client);
        
        _setiShardChannel=_oort.getBayeux().getChannel("/seti/"+shardId,true);
        _setiShardChannel.setPersistent(true);
        _oort.observeChannel(_setiShardChannel.getId());
        _setiShardChannel.subscribe(_client);
        
        _allShardLocation = new ShardLocation(_setiAllChannel);
         
    }

    /* ------------------------------------------------------------ */
    protected void doStart()
        throws Exception
    {
        super.doStart();
        _client.addListener(new MessageListener()
        {
            public void deliver(Client from, Client to, Message msg)
            {
                receive(from,to,msg);
            }
        });
    }
    
    /* ------------------------------------------------------------ */
    protected void doStop()
        throws Exception
    {
        _client.disconnect();
    }
    
    /* ------------------------------------------------------------ */
    public void associate(final String userId,final Client client)
    {
        _uid2Location.put(userId,new LocalLocation(client));
        userId2Shard(userId).associate(userId);
    }

    /* ------------------------------------------------------------ */
    public void disassociate(final String userId)
    {
        _uid2Location.remove(userId);
        userId2Shard(userId).disassociate(userId);
    }

    /* ------------------------------------------------------------ */
    public void sendMessage(final String toUser,final String toChannel,final Object message)
    {
        Location location = _uid2Location.get(toUser);
        if (location==null)
            location = userId2Shard(toUser);
        
        location.sendMessage(toUser,toChannel,message);
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
    protected void receive(final Client from, final Client to, final Message msg)
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
            
            if (location==null && _setiChannelId.equals(msg.getChannel()))
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
        public void sendMessage(String toUser,String toChannel,Object message);
        public void receive(String toUser,String toChannel,Object message);
    }
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class LocalLocation implements Location
    {
        Client _client;
        
        LocalLocation(Client client)
        {
            _client=client;
        }

        public void sendMessage(String toUser, String toChannel, Object message)
        {
            _client.deliver(Seti.this._client,toChannel,message,null);
        }

        public void receive(String toUser, String toChannel, Object message)
        {
            _client.deliver(Seti.this._client,toChannel,message,null);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class SetiLocation implements Location
    {
        Channel _channel;

        SetiLocation(String channelId)
        {
            _channel=_oort._bayeux.getChannel(channelId,true);
        }
        
        SetiLocation(Channel channel)
        {
            _channel=channel;
        }
        
        public void sendMessage(String toUser, String toChannel, Object message)
        {
            _channel.publish(Seti.this._client,new SetiMessage(toUser,toChannel,message),null);
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
        Channel _channel;
        
        ShardLocation(String shardId)
        {
            _channel=_oort._bayeux.getChannel("/seti/"+shardId,true);
            
        }
        
        ShardLocation(Channel channel)
        {
            _channel=channel;
        }
        
        public void sendMessage(final Collection<String> toUsers, final String toChannel, final Object message)
        {
            _channel.publish(Seti.this._client,new SetiMessage(toUsers,toChannel,message),null);
        }

        public void sendMessage(String toUser, String toChannel, Object message)
        {
            _channel.publish(Seti.this._client,new SetiMessage(toUser,toChannel,message),null);
        }
        
        public void receive(String toUser, String toChannel, Object message)
        {
            
        }
        
        public void associate(final String user)
        {
            _channel.publish(Seti.this._client,new SetiPresence(user,true),null);
        }
        
        public void disassociate(final String user)
        {
            _channel.publish(Seti.this._client,new SetiPresence(user,false),null);
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
    
}
