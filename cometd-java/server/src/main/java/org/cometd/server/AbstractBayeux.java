// ========================================================================
// Copyright 2006 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.server;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.cometd.Bayeux;
import org.cometd.BayeuxListener;
import org.cometd.Channel;
import org.cometd.ChannelBayeuxListener;
import org.cometd.Client;
import org.cometd.ClientBayeuxListener;
import org.cometd.ConfigurableChannel;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.SecurityPolicy;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * @author gregw
 * @author aabeling: added JSONP transport
 */
public abstract class AbstractBayeux extends MessagePool implements Bayeux
{
    public static final Logger logger = Log.getLogger(Bayeux.class.getName());
    public static final ChannelId META_ID=new ChannelId(META);
    public static final ChannelId META_CONNECT_ID=new ChannelId(META_CONNECT);
    public static final ChannelId META_CLIENT_ID=new ChannelId(META_CLIENT);
    public static final ChannelId META_DISCONNECT_ID=new ChannelId(META_DISCONNECT);
    public static final ChannelId META_HANDSHAKE_ID=new ChannelId(META_HANDSHAKE);
    public static final ChannelId META_PING_ID=new ChannelId(META_PING);
    public static final ChannelId META_STATUS_ID=new ChannelId(META_STATUS);
    public static final ChannelId META_SUBSCRIBE_ID=new ChannelId(META_SUBSCRIBE);
    public static final ChannelId META_UNSUBSCRIBE_ID=new ChannelId(META_UNSUBSCRIBE);

    private final HashMap<String,Handler> _handlers=new HashMap<String,Handler>();
    private final ChannelImpl _root=new ChannelImpl("/",this);
    private final ConcurrentHashMap<String,ClientImpl> _clients=new ConcurrentHashMap<String,ClientImpl>();
    protected final ConcurrentHashMap<String,ChannelId> _channelIdCache=new ConcurrentHashMap<String,ChannelId>();
    protected final ConcurrentHashMap<String,List<String>> _browser2client=new ConcurrentHashMap<String,List<String>>();
    protected final ThreadLocal<HttpServletRequest> _request=new ThreadLocal<HttpServletRequest>();
    protected final List<ClientBayeuxListener> _clientListeners=new CopyOnWriteArrayList<ClientBayeuxListener>();
    protected final List<ChannelBayeuxListener> _channelListeners=new CopyOnWriteArrayList<ChannelBayeuxListener>();
    private final List<ConfigurableChannel.Initializer> _initialChannelListeners=new CopyOnWriteArrayList<ConfigurableChannel.Initializer>();
    protected final Handler _publishHandler;
    protected final Handler _metaPublishHandler;

    protected SecurityPolicy _securityPolicy=new DefaultPolicy();
    protected JSON.Literal _advice;
    protected JSON.Literal _multiFrameAdvice;
    protected int _adviceVersion=0;
    protected Object _handshakeAdvice=new JSON.Literal("{\"reconnect\":\"handshake\",\"interval\":500}");
    protected int _logLevel;
    protected long _timeout=30000;
    protected long _interval=0;
    protected long _maxInterval=10000;
    protected boolean _initialized;
    protected int _multiFrameInterval=-1;
    private int _channelIdCacheLimit=0;

    protected boolean _requestAvailable;

    private ServletContext _context;
    protected Random _random;
    protected int _maxClientQueue=-1;

    protected Extension[] _extensions;
    protected JSON.Literal _transports=new JSON.Literal("[\"" + Bayeux.TRANSPORT_LONG_POLL + "\",\"" + Bayeux.TRANSPORT_CALLBACK_POLL + "\"]");

    protected int _maxLazyLatency=5000;

    /* ------------------------------------------------------------ */
    protected AbstractBayeux()
    {
        _publishHandler=new PublishHandler();
        _metaPublishHandler=new MetaPublishHandler();
        _handlers.put(META_HANDSHAKE,new HandshakeHandler());
        _handlers.put(META_CONNECT,new ConnectHandler());
        _handlers.put(META_DISCONNECT,new DisconnectHandler());
        _handlers.put(META_SUBSCRIBE,new SubscribeHandler());
        _handlers.put(META_UNSUBSCRIBE,new UnsubscribeHandler());
        _handlers.put(META_PING,new PingHandler());

        setTimeout(getTimeout());
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension ext)
    {
        _extensions=(Extension[])LazyList.addToArray(_extensions, ext, Extension.class);
    }

    public void removeExtension(Extension ext)
    {
        _extensions = (Extension[])LazyList.removeFromArray(_extensions, ext);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param id the channel id
     * @return the ChannelImpl instance with the given id
     */
    public ChannelImpl getChannel(ChannelId id)
    {
        return _root.getChild(id);
    }

    /* ------------------------------------------------------------ */
    public ChannelImpl getChannel(String id)
    {
        ChannelId cid=getChannelId(id);
        if (cid.depth() == 0)
            return null;
        return _root.getChild(cid);
    }

    /* ------------------------------------------------------------ */
    public Channel getChannel(String id, boolean create)
    {
        ChannelImpl channel=getChannel(id);

        if (channel == null && create)
        {
            channel=new ChannelImpl(id,this);
            Channel added =_root.addChild(channel);
            if (added!=channel)
                return added;
            if (isLogInfo())
                logInfo("newChannel: " + channel);
        }
        return channel;
    }

    /* ------------------------------------------------------------ */
    public ChannelId getChannelId(String id)
    {
        if (_channelIdCacheLimit<0)
            return new ChannelId(id);

        ChannelId cid=_channelIdCache.get(id);
        if (cid == null)
        {
            cid=new ChannelId(id);
            if (_channelIdCacheLimit>0 && _channelIdCache.size()>_channelIdCacheLimit)
                _channelIdCache.clear();
            ChannelId other=_channelIdCache.putIfAbsent(id,cid);
            if (other!=null)
                return other;
        }
        return cid;
    }

    /* ------------------------------------------------------------ */
    public Client getClient(String client_id)
    {
        if (client_id == null)
            return null;
        return _clients.get(client_id);
    }

    /* ------------------------------------------------------------ */
    public Set<String> getClientIDs()
    {
        return _clients.keySet();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The maximum time in ms to wait between polls before timing out a
     *         client
     */
    public long getMaxInterval()
    {
        return _maxInterval;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the logLevel. 0=none, 1=info, 2=debug
     */
    public int getLogLevel()
    {
        return _logLevel;
    }

    /* ------------------------------------------------------------ */
    public SecurityPolicy getSecurityPolicy()
    {
        return _securityPolicy;
    }

    /* ------------------------------------------------------------ */
    public long getTimeout()
    {
        return _timeout;
    }

    /* ------------------------------------------------------------ */
    public long getInterval()
    {
        return _interval;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if published messages are directly delivered to subscribers.
     *         False if a new message is to be created that holds only supported
     *         fields.
     */
    public boolean isDirectDeliver()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated
     * @param directDeliver
     *            true if published messages are directly delivered to
     *            subscribers. False if a new message is to be created that
     *            holds only supported fields.
     */
    public void setDirectDeliver(boolean directDeliver)
    {
        _context.log("directDeliver is deprecated");
    }

    /* ------------------------------------------------------------ */
    /**
     * Handle a Bayeux message. This is normally only called by the bayeux
     * servlet or a test harness.
     *
     * @param client The client if known
     * @param transport The transport to use for the message
     * @param message The bayeux message.
     * @return the channel id
     * @throws IOException if the handle fails
     */
    public String handle(ClientImpl client, Transport transport, Message message) throws IOException
    {
        String channel_id = message.getChannel();
        if (channel_id == null)
            throw new IllegalArgumentException("Message without channel: "+message);

        Handler handler = _handlers.get(channel_id);
        if (handler != null)
        {
            message=extendRcvMeta(client,message);
            handler.handle(client,transport,message);
            _metaPublishHandler.handle(client, transport, message);
        }
        else if (channel_id.startsWith(META_SLASH))
        {
            message = extendRcvMeta(client, message);
            _metaPublishHandler.handle(client, transport, message);
        }
        else
        {
            // Non meta channel
            handler = _publishHandler;
            message = extendRcv(client,message);
            handler.handle(client, transport, message);
        }

        return channel_id;
    }

    /* ------------------------------------------------------------ */
    public boolean hasChannel(String id)
    {
        ChannelId cid=getChannelId(id);
        return _root.getChild(cid) != null;
    }

    /* ------------------------------------------------------------ */
    public boolean isInitialized()
    {
        return _initialized;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the commented
     * @deprecated
     */
    public boolean isJSONCommented()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public boolean isLogDebug()
    {
        return _logLevel > 1;
    }

    /* ------------------------------------------------------------ */
    public boolean isLogInfo()
    {
        return _logLevel > 0;
    }

    /* ------------------------------------------------------------ */
    public void logDebug(String message)
    {
        if (_logLevel > 1)
            _context.log(message);
    }

    /* ------------------------------------------------------------ */
    public void logDebug(String message, Throwable th)
    {
        if (_logLevel > 1)
            _context.log(message,th);
    }

    /* ------------------------------------------------------------ */
    public void logWarn(String message, Throwable th)
    {
        _context.log(message + ": " + th.toString());
    }

    /* ------------------------------------------------------------ */
    public void logWarn(String message)
    {
        _context.log(message);
    }

    /* ------------------------------------------------------------ */
    public void logInfo(String message)
    {
        if (_logLevel > 0)
            _context.log(message);
    }

    /* ------------------------------------------------------------ */
    public Client newClient(String idPrefix)
    {
        ClientImpl client=new ClientImpl(this,idPrefix);
        addClient(client,idPrefix);
        return client;
    }

    /* ------------------------------------------------------------ */
    public abstract ClientImpl newRemoteClient();

    /* ------------------------------------------------------------ */
    /**
     * Create new transport object for a bayeux message
     *
     * @param client
     *            The client
     * @param message
     *            the bayeux message
     * @return the negotiated transport.
     */
    public Transport newTransport(ClientImpl client, Map<?,?> message)
    {
        if (isLogDebug())
            logDebug("newTransport: client=" + client + ",message=" + message);

        Transport result;

        String type = client == null ? null : client.getConnectionType();
        if (type == null)
        {
            // Check if it is a connect message and we can extract the connection type
            type = (String)message.get(Bayeux.CONNECTION_TYPE_FIELD);
        }
        if (type == null)
        {
            // Check if it is an handshake message and we can negotiate the connection type
            Object types = message.get(Bayeux.SUPPORTED_CONNECTION_TYPES_FIELD);
            if (types != null)
            {
                List supportedTypes;
                if (types instanceof Object[]) supportedTypes = Arrays.asList((Object[])types);
                else if (types instanceof List) supportedTypes = (List)types;
                else if (types instanceof Map) supportedTypes = new ArrayList(((Map)types).values());
                else supportedTypes = Collections.emptyList();

                if (supportedTypes.contains(Bayeux.TRANSPORT_LONG_POLL)) type = Bayeux.TRANSPORT_LONG_POLL;
                else if (supportedTypes.contains(Bayeux.TRANSPORT_CALLBACK_POLL)) type = Bayeux.TRANSPORT_CALLBACK_POLL;
            }
        }
        if (type == null)
        {
            // A normal message, check if it has the jsonp parameter
            String jsonp = (String) message.get(Bayeux.JSONP_PARAMETER);
            type = jsonp != null ? Bayeux.TRANSPORT_CALLBACK_POLL : Bayeux.TRANSPORT_LONG_POLL;
        }

        if (Bayeux.TRANSPORT_CALLBACK_POLL.equals(type))
        {
            String jsonp = (String)message.get(Bayeux.JSONP_PARAMETER);
            if (jsonp == null) throw new IllegalArgumentException("Missing 'jsonp' field in message " + message + " for transport " + type);
            result = new JSONPTransport(jsonp);
        }
        else if (Bayeux.TRANSPORT_LONG_POLL.equals(type))
        {
            result = new JSONTransport();
        }
        else
        {
            throw new IllegalArgumentException("Unsupported transport type " + type);
        }

        if (isLogDebug())
            logDebug("newTransport: result="+result);

        return result;
    }

    /* ------------------------------------------------------------ */
    /**
     * Publish data to a channel. Creates a message and delivers it to the root channel.
     *
     * @param to the channel id to publish to
     * @param from the client that publishes
     * @param data the data to publish
     * @param msgId the message id
     * @param lazy whether the message is published lazily
     */
    protected void doPublish(ChannelId to, Client from, Object data, String msgId, boolean lazy)
    {
        final MessageImpl message=newMessage();
        message.put(CHANNEL_FIELD,to.toString());

        if (msgId == null)
        {
            long id=message.hashCode() ^ (to == null?0:to.hashCode()) ^ (from == null?0:from.hashCode());
            id=id < 0?-id:id;
            message.put(ID_FIELD,Long.toString(id,36));
        }
        else
            message.put(ID_FIELD,msgId);
        message.put(DATA_FIELD,data);

        message.setLazy(lazy);

        final Message m=extendSendBayeux(from,message);

        if (m != null)
            _root.doDelivery(to,from,m);
        if (m instanceof MessageImpl)
            ((MessageImpl)m).decRef();
    }

    /* ------------------------------------------------------------ */
    public boolean removeChannel(ChannelImpl channel)
    {
        return _root.doRemove(channel,_channelListeners);
    }

    protected void initChannel(ConfigurableChannel channel)
    {
        for (ConfigurableChannel.Initializer listener : _initialChannelListeners)
        {
            try
            {
                listener.configureChannel(channel);
            }
            catch (RuntimeException x)
            {
                logger.info("Unexpected exception while invoking listener " + listener, x);
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected void addChannel(ChannelImpl channel)
    {
        for (ChannelBayeuxListener listener : _channelListeners)
        {
            try
            {
                listener.channelAdded(channel);
            }
            catch (RuntimeException x)
            {
                logger.info("Unexpected exception while invoking listener " + listener, x);
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected String newClientId(long variation, String idPrefix)
    {
        if (idPrefix == null)
            return Long.toString(getRandom(),36) + Long.toString(variation,36);
        else
            return idPrefix + "_" + Long.toString(getRandom(),36);
    }

    /* ------------------------------------------------------------ */
    protected void addClient(ClientImpl client, String idPrefix)
    {
        while(true)
        {
            String id=newClientId(client.hashCode(),idPrefix);
            client.setId(id);

            ClientImpl other=_clients.putIfAbsent(id,client);
            if (other == null)
            {
                for (ClientBayeuxListener l : _clientListeners)
                    l.clientAdded(client);
                if (isLogInfo())
                    logInfo("Added client: " + client);
                return;
            }
        }
    }

    /* ------------------------------------------------------------ */
    public Client removeClient(String client_id)
    {
        ClientImpl client;
        if (client_id == null)
            return null;
        client=_clients.remove(client_id);
        if (client != null)
        {
            for (ClientBayeuxListener l : _clientListeners)
                l.clientRemoved(client);
            client.unsubscribeAll();
            if (isLogInfo())
                logInfo("Removed client: " + client);
        }
        return client;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param ms
     *            The maximum time in ms to wait between polls before timing out
     *            a client
     */
    public void setMaxInterval(long ms)
    {
        _maxInterval=ms;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param commented the commented to set
     */
    public void setJSONCommented(boolean commented)
    {
        if (commented)
            _context.log("JSONCommented is deprecated");
    }

    /* ------------------------------------------------------------ */
    /**
     * @param logLevel
     *            the logLevel: 0=none, 1=info, 2=debug
     */
    public void setLogLevel(int logLevel)
    {
        _logLevel=logLevel;
    }

    /* ------------------------------------------------------------ */
    public void setSecurityPolicy(SecurityPolicy securityPolicy)
    {
        _securityPolicy=securityPolicy;
    }

    /* ------------------------------------------------------------ */
    public void setTimeout(long ms)
    {
        _timeout=ms;
        generateAdvice();
    }

    /* ------------------------------------------------------------ */
    public void setInterval(long ms)
    {
        _interval=ms;
        generateAdvice();
    }

    /* ------------------------------------------------------------ */
    /**
     * The time a client should delay between reconnects when multiple
     * connections from the same browser are detected. This effectively produces
     * traditional polling.
     *
     * @param multiFrameInterval
     *            the multiFrameInterval to set
     */
    public void setMultiFrameInterval(int multiFrameInterval)
    {
        _multiFrameInterval=multiFrameInterval;
        generateAdvice();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the multiFrameInterval in milliseconds
     */
    public int getMultiFrameInterval()
    {
        return _multiFrameInterval;
    }

    /**
     * @return the limit of the {@link ChannelId} cache
     * @see #setChannelIdCacheLimit(int)
     */
    public int getChannelIdCacheLimit()
    {
        return _channelIdCacheLimit;
    }

    /**
     * Sets the cache limit for {@link ChannelId}s: use -1 to disable the cache, 0 for
     * an unlimited cache, and any positive value for the limit after which the cache
     * is cleared.
     * @param channelIdCacheLimit the limit of the {@link ChannelId} cache
     * @see #getChannelIdCacheLimit()
     */
    public void setChannelIdCacheLimit(int channelIdCacheLimit)
    {
        this._channelIdCacheLimit = channelIdCacheLimit;
    }

    /* ------------------------------------------------------------ */
    void generateAdvice()
    {
        setAdvice(new JSON.Literal("{\"reconnect\":\"retry\",\"interval\":" + getInterval() + ",\"timeout\":" + getTimeout() + "}"));
    }

    /* ------------------------------------------------------------ */
    public void setAdvice(JSON.Literal advice)
    {
        synchronized(this)
        {
            _adviceVersion++;
            _advice=advice;
            _multiFrameAdvice=new JSON.Literal(JSON.toString(multiFrameAdvice(advice)));
        }
    }

    /* ------------------------------------------------------------ */
    private Map<String,Object> multiFrameAdvice(JSON.Literal advice)
    {
        Map<String,Object> a=(Map<String,Object>)JSON.parse(_advice.toString());
        a.put("multiple-clients",Boolean.TRUE);
        if (_multiFrameInterval > 0)
        {
            a.put("reconnect","retry");
            a.put("interval",_multiFrameInterval);
        }
        else
            a.put("reconnect","none");
        return a;
    }

    /* ------------------------------------------------------------ */
    public JSON.Literal getAdvice()
    {
        return _advice;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return TRUE if {@link #getCurrentRequest()} will return the current
     *         request
     */
    public boolean isRequestAvailable()
    {
        return _requestAvailable;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param requestAvailable
     *            TRUE if {@link #getCurrentRequest()} will return the current
     *            request
     */
    public void setRequestAvailable(boolean requestAvailable)
    {
        _requestAvailable=requestAvailable;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the current request if {@link #isRequestAvailable()} is true,
     *         else null
     */
    public HttpServletRequest getCurrentRequest()
    {
        return _request.get();
    }

    /* ------------------------------------------------------------ */
    void setCurrentRequest(HttpServletRequest request)
    {
        _request.set(request);
    }

    /* ------------------------------------------------------------ */
    public Collection<Channel> getChannels()
    {
        List<Channel> channels=new ArrayList<Channel>();
        _root.getChannels(channels);
        return channels;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the number of channels
     */
    public int getChannelCount()
    {
        return getChannels().size();
    }

    /* ------------------------------------------------------------ */
    public Collection<Client> getClients()
    {
        return new ArrayList<Client>(_clients.values());
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the number of clients
     */
    public int getClientCount()
    {
        return _clients.size();
    }

    /* ------------------------------------------------------------ */
    public boolean hasClient(String clientId)
    {
        if (clientId == null)
            return false;
        return _clients.containsKey(clientId);
    }

    /* ------------------------------------------------------------ */
    public Channel removeChannel(String channelId)
    {
        Channel channel=getChannel(channelId);

        boolean removed=false;
        if (channel != null)
            removed=channel.remove();

        if (removed)
            return channel;
        else
            return null;
    }

    /* ------------------------------------------------------------ */
    protected void initialize(ServletContext context)
    {
        synchronized(this)
        {
            _initialized=true;
            _context=context;
            try
            {
                _random=SecureRandom.getInstance("SHA1PRNG");
            }
            catch(Exception e)
            {
                context.log("Could not get secure random for ID generation",e);
                _random=new Random();
            }
            _random.setSeed(_random.nextLong() ^ hashCode() ^ System.nanoTime() ^ Runtime.getRuntime().freeMemory());

            _root.addChild(new ServiceChannel(Bayeux.SERVICE));

        }
    }

    /* ------------------------------------------------------------ */
    long getRandom()
    {
        long l=_random.nextLong();
        return l < 0?-l:l;
    }

    /* ------------------------------------------------------------ */
    void clientOnBrowser(String browserId, String clientId)
    {
        List<String> clients=_browser2client.get(browserId);
        if (clients == null)
        {
            List<String> new_clients=new CopyOnWriteArrayList<String>();
            clients=_browser2client.putIfAbsent(browserId,new_clients);
            if (clients == null)
                clients=new_clients;
        }
        clients.add(clientId);
    }

    /* ------------------------------------------------------------ */
    void clientOffBrowser(String browserId, String clientId)
    {
        List<String> clients=_browser2client.get(browserId);
        if (clients != null)
        {
            clients.remove(clientId);
            if (clients.isEmpty())
                _browser2client.remove(browserId);
        }
    }

    /* ------------------------------------------------------------ */
    List<String> clientsOnBrowser(String browserId)
    {
        return _browser2client.get(browserId);
    }

    /* ------------------------------------------------------------ */
    public void addListener(BayeuxListener listener)
    {
        if (listener instanceof ClientBayeuxListener)
            _clientListeners.add((ClientBayeuxListener)listener);
        if (listener instanceof ChannelBayeuxListener)
            _channelListeners.add((ChannelBayeuxListener)listener);
        if (listener instanceof ConfigurableChannel.Initializer)
            _initialChannelListeners.add((ConfigurableChannel.Initializer)listener);
    }

    public void removeListener(BayeuxListener listener)
    {
        if (listener instanceof ClientBayeuxListener)
            _clientListeners.remove(listener);
        if (listener instanceof ChannelBayeuxListener)
            _channelListeners.remove(listener);
        if (listener instanceof ConfigurableChannel.Initializer)
            _initialChannelListeners.remove(listener);
    }

    /* ------------------------------------------------------------ */
    public int getMaxClientQueue()
    {
        return _maxClientQueue;
    }

    /* ------------------------------------------------------------ */
    public void setMaxClientQueue(int size)
    {
        _maxClientQueue=size;
    }

    /* ------------------------------------------------------------ */
    protected Message extendRcv(ClientImpl from, Message message)
    {
        if (_extensions != null)
        {
            for (int i=_extensions.length; message != null && i-- > 0;)
                message=_extensions[i].rcv(from,message);
        }

        if (from != null)
        {
            Extension[] client_exs=from.getExtensions();
            if (client_exs != null)
            {
                for (int i=client_exs.length; message != null && i-- > 0;)
                    message=client_exs[i].rcv(from,message);
            }
        }

        return message;
    }

    /* ------------------------------------------------------------ */
    protected Message extendRcvMeta(ClientImpl from, Message message)
    {
        if (_extensions != null)
        {
            for (int i=_extensions.length; message != null && i-- > 0;)
                message=_extensions[i].rcvMeta(from,message);
        }

        if (from != null)
        {
            Extension[] client_exs=from.getExtensions();
            if (client_exs != null)
            {
                for (int i=client_exs.length; message != null && i-- > 0;)
                    message=client_exs[i].rcvMeta(from,message);
            }
        }
        return message;
    }

    /* ------------------------------------------------------------ */
    protected Message extendSendBayeux(Client from, Message message)
    {
        if (_extensions != null)
        {
            for (int i=0; message != null && i < _extensions.length; i++)
            {
                message=_extensions[i].send(from,message);
            }
        }

        return message;
    }

    /* ------------------------------------------------------------ */
    public Message extendSendClient(Client from, ClientImpl to, Message message)
    {
        if (to != null)
        {
            Extension[] client_exs=to.getExtensions();
            if (client_exs != null)
            {
                for (int i=0; message != null && i < client_exs.length; i++)
                    message=client_exs[i].send(from,message);
            }
        }

        return message;
    }

    /* ------------------------------------------------------------ */
    public Message extendSendMeta(ClientImpl from, Message message)
    {
        if (_extensions != null)
        {
            for (int i=0; message != null && i < _extensions.length; i++)
                message=_extensions[i].sendMeta(from,message);
        }

        if (from != null)
        {
            Extension[] client_exs=from.getExtensions();
            if (client_exs != null)
            {
                for (int i=0; message != null && i < client_exs.length; i++)
                    message=client_exs[i].sendMeta(from,message);
            }
        }

        return message;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the maximum ms that a lazy message will wait before
     * resuming waiting client
     */
    public int getMaxLazyLatency()
    {
        return _maxLazyLatency;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param ms the maximum ms that a lazy message will wait before
     * resuming waiting client
     */
    public void setMaxLazyLatency(int ms)
    {
        _maxLazyLatency = ms;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static class DefaultPolicy implements SecurityPolicy
    {
        public boolean canHandshake(Message message)
        {
            return true;
        }

        public boolean canCreate(Client client, String channel, Message message)
        {
            return client != null && !channel.startsWith(Bayeux.META_SLASH);
        }

        public boolean canSubscribe(Client client, String channel, Message message)
        {
            if (client != null && ("/**".equals(channel) || "/*".equals(channel)))
                return false;
            return client != null && !channel.startsWith(Bayeux.META_SLASH);
        }

        public boolean canPublish(Client client, String channel, Message message)
        {
            return client != null || Bayeux.META_HANDSHAKE.equals(channel);
        }

    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected abstract class Handler
    {
        abstract void handle(ClientImpl client, Transport transport, Message message) throws IOException;

        abstract ChannelId getMetaChannelId();

        protected boolean isClientUnknown(Client client)
        {
            return client == null || !hasClient(client.getId());
        }

        void unknownClient(Transport transport, String channel) throws IOException
        {
            MessageImpl reply=newMessage();

            reply.put(CHANNEL_FIELD,channel);
            reply.put(SUCCESSFUL_FIELD,Boolean.FALSE);
            reply.put(ERROR_FIELD,"402::Unknown client");
            reply.put("advice",_handshakeAdvice);
            transport.send(reply);
            reply.decRef();
        }

        void sendMetaReply(final ClientImpl client, Message reply, final Transport transport) throws IOException
        {
            reply=extendSendMeta(client,reply);
            if (reply != null)
            {
                transport.send(reply);
                if (reply instanceof MessageImpl)
                    ((MessageImpl)reply).decRef();
            }
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class ConnectHandler extends Handler
    {
        protected String _metaChannel=META_CONNECT;

        @Override
        ChannelId getMetaChannelId()
        {
            return META_CONNECT_ID;
        }

        @Override
        public void handle(ClientImpl client, Transport transport, Message message) throws IOException
        {
            if (isClientUnknown(client))
            {
                unknownClient(transport,_metaChannel);
                return;
            }

            // is this the first connect message?
            String type=client.getConnectionType();
            boolean polling=true;
            if (type == null)
            {
                type=(String)message.get(Bayeux.CONNECTION_TYPE_FIELD);
                client.setConnectionType(type);
                polling=false;
            }

            Object advice=message.get(ADVICE_FIELD);
            if (advice != null)
            {
                Long timeout=(Long)((Map)advice).get("timeout");
                if (timeout != null && timeout >= 0L)
                    client.setTimeout(timeout);
                else
                    client.setTimeout(-1);

                Long interval=(Long)((Map)advice).get("interval");
                if (interval != null && interval >= 0L)
                    client.setInterval(interval);
                else
                    client.setInterval(-1);
            }
            else
            {
                client.setTimeout(-1);
                client.setInterval(-1);
            }

            advice=null;

            // Work out if multiple clients from some browser?
            if (polling && _multiFrameInterval > 0 && client.getBrowserId() != null)
            {
                List<String> clients=clientsOnBrowser(client.getBrowserId());
                if (clients != null && clients.size() > 1)
                {
                    polling=clients.get(0).equals(client.getId());
                    advice=client.getAdvice();
                    if (advice == null)
                        advice=_multiFrameAdvice;
                    else
                        // could probably cache this
                        advice=multiFrameAdvice((JSON.Literal)advice);
                }
            }

            synchronized(this)
            {
                if (advice == null)
                {
                    if (_adviceVersion != client._adviseVersion)
                    {
                        advice=_advice;
                        client._adviseVersion=_adviceVersion;
                    }
                }
                else
                    client._adviseVersion=-1; // clear so it is reset after multi state clears
            }

            // reply to connect message
            String id=message.getId();

            Message reply=newMessage(message);

            reply.put(CHANNEL_FIELD,META_CONNECT);
            reply.put(SUCCESSFUL_FIELD,Boolean.TRUE);
            if (advice != null)
                reply.put(ADVICE_FIELD,advice);
            if (id != null)
                reply.put(ID_FIELD,id);

            if (polling)
                transport.setMetaConnectReply(reply);
            else
                sendMetaReply(client,reply,transport);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class DisconnectHandler extends Handler
    {
        @Override
        ChannelId getMetaChannelId()
        {
            return META_DISCONNECT_ID;
        }

        @Override
        public void handle(ClientImpl client, Transport transport, Message message) throws IOException
        {
            if (isClientUnknown(client))
            {
                unknownClient(transport,META_DISCONNECT);
                return;
            }
            if (isLogInfo())
                logInfo("Disconnect " + client.getId());

            client.remove(false);

            Message reply=newMessage(message);
            reply.put(CHANNEL_FIELD,META_DISCONNECT);
            reply.put(SUCCESSFUL_FIELD,Boolean.TRUE);
            String id=message.getId();
            if (id != null)
                reply.put(ID_FIELD,id);

            Message pollReply=transport.getMetaConnectReply();
            if (pollReply != null)
            {
                transport.setMetaConnectReply(null);
                sendMetaReply(client,pollReply,transport);
            }
            sendMetaReply(client,reply,transport);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class HandshakeHandler extends Handler
    {
        @Override
        ChannelId getMetaChannelId()
        {
            return META_HANDSHAKE_ID;
        }

        @Override
        public void handle(ClientImpl client, Transport transport, Message message) throws IOException
        {
            if (client != null)
                throw new IllegalStateException();

            if (_securityPolicy != null && !_securityPolicy.canHandshake(message))
            {
                Message reply=newMessage(message);
                reply.put(CHANNEL_FIELD,META_HANDSHAKE);
                reply.put(SUCCESSFUL_FIELD,Boolean.FALSE);
                reply.put(ERROR_FIELD,"403::Handshake denied");

                sendMetaReply(client,reply,transport);
                return;
            }

            client=newRemoteClient();

            Message reply=newMessage(message);
            reply.put(CHANNEL_FIELD,META_HANDSHAKE);
            reply.put(VERSION_FIELD,"1.0");
            reply.put(MIN_VERSION_FIELD,"0.9");

            if (client != null)
            {
                reply.put(SUPPORTED_CONNECTION_TYPES_FIELD,_transports);
                reply.put(SUCCESSFUL_FIELD,Boolean.TRUE);
                reply.put(CLIENT_FIELD,client.getId());
                if (_advice != null)
                    reply.put(ADVICE_FIELD,_advice);
            }
            else
            {
                reply.put(Bayeux.SUCCESSFUL_FIELD,Boolean.FALSE);
                if (_advice != null)
                    reply.put(ADVICE_FIELD,_advice);
            }

            if (isLogDebug())
                logDebug("handshake.handle: reply=" + reply);

            String id=message.getId();
            if (id != null)
                reply.put(ID_FIELD,id);

            sendMetaReply(client,reply,transport);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class PublishHandler extends Handler
    {
        @Override
        ChannelId getMetaChannelId()
        {
            return null;
        }

        @Override
        public void handle(ClientImpl client, Transport transport, Message message) throws IOException
        {
            if (message == null)
            {
                Message reply = newMessage(message);
                reply.put(SUCCESSFUL_FIELD, Boolean.FALSE);
                reply.put(ERROR_FIELD, "404::Message deleted");
                sendMetaReply(client, reply, transport);
                return;
            }

            String channel_id=message.getChannel();
            if (isClientUnknown(client) && message.containsKey(CLIENT_FIELD))
            {
                unknownClient(transport,channel_id);
                return;
            }

            String id=message.getId();

            ChannelId cid=getChannelId(channel_id);
            Object data=message.get(Bayeux.DATA_FIELD);

            Message reply=newMessage(message);
            reply.put(CHANNEL_FIELD,channel_id);
            if (id != null)
                reply.put(ID_FIELD,id);

            if (data == null)
            {
                message=null;
                reply.put(SUCCESSFUL_FIELD,Boolean.FALSE);
                reply.put(ERROR_FIELD,"403::No data");
            }
            else if (!_securityPolicy.canPublish(client,channel_id,message))
            {
                message=null;
                reply.put(SUCCESSFUL_FIELD,Boolean.FALSE);
                reply.put(ERROR_FIELD,"403::Publish denied");
            }
            else
            {
                reply.put(SUCCESSFUL_FIELD,Boolean.TRUE);
            }

            sendMetaReply(client,reply,transport);

            if (message != null)
                _root.doDelivery(cid,client,message);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class MetaPublishHandler extends Handler
    {
        @Override
        ChannelId getMetaChannelId()
        {
            return null;
        }

        @Override
        public void handle(ClientImpl client, Transport transport, Message message) throws IOException
        {
            String channel_id=message.getChannel();

            if (isClientUnknown(client) && !META_HANDSHAKE.equals(channel_id) && !META_DISCONNECT.equals(channel_id))
            {
                // unknown client
                return;
            }

            if (_securityPolicy.canPublish(client,channel_id,message))
            {
                _root.doDelivery(getChannelId(channel_id),client,message);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class SubscribeHandler extends Handler
    {
        @Override
        ChannelId getMetaChannelId()
        {
            return META_SUBSCRIBE_ID;
        }

        @Override
        public void handle(ClientImpl client, Transport transport, Message message) throws IOException
        {
            if (isClientUnknown(client))
            {
                unknownClient(transport,META_SUBSCRIBE);
                return;
            }

            String subscribe_id=(String)message.get(SUBSCRIPTION_FIELD);

            // select a random channel ID if none specifified
            if (subscribe_id == null)
            {
                subscribe_id=Long.toString(getRandom(),36);
                while(getChannel(subscribe_id) != null)
                    subscribe_id=Long.toString(getRandom(),36);
            }

            ChannelId cid=null;
            boolean can_subscribe=false;

            if (subscribe_id.startsWith(Bayeux.SERVICE_SLASH))
            {
                can_subscribe=true;
            }
            else if (subscribe_id.startsWith(Bayeux.META_SLASH))
            {
                can_subscribe=false;
            }
            else
            {
                cid=getChannelId(subscribe_id);
                can_subscribe=_securityPolicy.canSubscribe(client,subscribe_id,message);
            }

            Message reply=newMessage(message);
            reply.put(CHANNEL_FIELD,META_SUBSCRIBE);
            reply.put(SUBSCRIPTION_FIELD,subscribe_id);

            if (can_subscribe)
            {
                if (cid != null)
                {
                    ChannelImpl channel=getChannel(cid);
                    if (channel == null && _securityPolicy.canCreate(client,subscribe_id,message))
                        channel=(ChannelImpl)getChannel(subscribe_id,true);

                    if (channel != null)
                    {
                        // Reduces the window of time where a server-side expiration
                        // or a concurrent disconnect causes the invalid client to be
                        // registered as subscriber and hence being kept alive by the
                        // fact that the channel references it.
                        if (isClientUnknown(client))
                        {
                            unknownClient(transport, META_SUBSCRIBE);
                            return;
                        }
                        else
                        {
                            channel.subscribe(client);
                        }
                    }
                    else
                    {
                        can_subscribe=false;
                    }
                }

                if (can_subscribe)
                {
                    reply.put(SUCCESSFUL_FIELD,Boolean.TRUE);
                }
                else
                {
                    reply.put(SUCCESSFUL_FIELD,Boolean.FALSE);
                    reply.put(ERROR_FIELD,"403::cannot create");
                }
            }
            else
            {
                reply.put(SUCCESSFUL_FIELD,Boolean.FALSE);
                reply.put(ERROR_FIELD,"403::cannot subscribe");

            }

            String id=message.getId();
            if (id != null)
                reply.put(ID_FIELD,id);

            sendMetaReply(client,reply,transport);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class UnsubscribeHandler extends Handler
    {
        @Override
        ChannelId getMetaChannelId()
        {
            return META_UNSUBSCRIBE_ID;
        }

        @Override
        public void handle(ClientImpl client, Transport transport, Message message) throws IOException
        {
            if (isClientUnknown(client))
            {
                unknownClient(transport,META_UNSUBSCRIBE);
                return;
            }

            String channel_id=(String)message.get(SUBSCRIPTION_FIELD);
            ChannelImpl channel=getChannel(channel_id);
            if (channel != null)
                channel.unsubscribe(client);

            Message reply=newMessage(message);
            reply.put(CHANNEL_FIELD,META_UNSUBSCRIBE);
            reply.put(SUBSCRIPTION_FIELD,channel_id);
            reply.put(SUCCESSFUL_FIELD,Boolean.TRUE);

            String id=message.getId();
            if (id != null)
                reply.put(ID_FIELD,id);

            sendMetaReply(client,reply,transport);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class PingHandler extends Handler
    {
        @Override
        ChannelId getMetaChannelId()
        {
            return META_PING_ID;
        }

        @Override
        public void handle(ClientImpl client, Transport transport, Message message) throws IOException
        {
            Message reply=newMessage(message);
            reply.put(CHANNEL_FIELD,META_PING);
            reply.put(SUCCESSFUL_FIELD,Boolean.TRUE);

            String id=message.getId();
            if (id != null)
                reply.put(ID_FIELD,id);

            sendMetaReply(client,reply,transport);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class ServiceChannel extends ChannelImpl
    {
        ServiceChannel(String id)
        {
            super(id,AbstractBayeux.this);
            setPersistent(true);
        }

        /* ------------------------------------------------------------ */
        @Override
        public ChannelImpl addChild(ChannelImpl channel)
        {
            channel.setPersistent(true);
            return super.addChild(channel);
        }

        /* ------------------------------------------------------------ */
        @Override
        public void subscribe(Client client)
        {
            if (client.isLocal())
                super.subscribe(client);
        }

    }
}
