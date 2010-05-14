package org.cometd.server.transports;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Queue;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.AbstractServerTransport;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;



public class WebSocketTransport extends HttpTransport
{
    public final static String NAME="websocket";
    public final static String PROTOCOL_OPTION="protocol";
    public final static String BUFFER_SIZE_OPTION="bufferSize";
    
    private final WebSocketFactory _factory = new WebSocketFactory();
    
    private ThreadLocal<Addresses> _addresses = new ThreadLocal<Addresses>();
    
    
    
    private String _protocol="";
    
    public WebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux,NAME);
        addPrefix("ws");
        setOption(PROTOCOL_OPTION,_protocol);
        setOption(BUFFER_SIZE_OPTION,_factory.getBufferSize());
        _metaConnectDeliveryOnly=false;
        setOption(META_CONNECT_DELIVERY_OPTION,_metaConnectDeliveryOnly);
        _timeout=15000;
        setOption(TIMEOUT_OPTION,_timeout);
        _interval=2500;
        setOption(INTERVAL_OPTION,_interval);
        _maxInterval=15000;
        setOption(MAX_INTERVAL_OPTION,_maxInterval);
    }

    @Override
    public void init()
    {
        _protocol=getOption(PROTOCOL_OPTION,_protocol);
        _factory.setBufferSize(getOption(BUFFER_SIZE_OPTION,_factory.getBufferSize()));
    }
    
    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "WebSocket".equals(request.getHeader("Upgrade"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        String protocol=request.getHeader("WebSocket-Protocol");

        String host=request.getHeader("Host");
        String origin=request.getHeader("Origin");
        origin=checkOrigin(request,host,origin);
        
        if (origin==null || _protocol!=null && _protocol.length()>0 && !_protocol.equals(protocol))
        {
            response.sendError(403);
            return;
        }
        
        Addresses addresses=new Addresses();
        addresses._local=new InetSocketAddress(request.getLocalAddr(),request.getLocalPort());
        addresses._remote=new InetSocketAddress(request.getRemoteAddr(),request.getRemotePort());
        
        WebSocket websocket = isMetaConnectDeliveryOnly()?null:new WebSocketDispatcher(addresses);
        
        if (websocket!=null)
            _factory.upgrade(request,response,websocket,origin,protocol);
        else
            response.sendError(403);
    }

    protected String checkOrigin(HttpServletRequest request, String host, String origin)
    {
        if (origin==null)
            origin=host;
        return origin;
    }
    
    protected class WebSocketDispatcher implements WebSocket, AbstractServerTransport.Dispatcher
    {
        protected final Addresses _addresses;
        protected ServerSessionImpl _session;
        protected Outbound _outbound;
        protected ServerMessage _connectReply;
        protected final Timeout.Task _timeoutTask = new Timeout.Task()
        {
            @Override
            public void expired()
            {
                // send the meta connect response after timeout.
                if (_session!=null && _session.setDispatcher(null))
                    dispatch();
            }
        };
        
        public WebSocketDispatcher(Addresses addresses)
        {
            _addresses=addresses;
        }
        
        public void onConnect(Outbound outbound)
        {
            _outbound = outbound;
        }

        public void onDisconnect()
        {
            if (_session!=null)
            {
                _session.cancelIntervalTimeout(); 
                _bayeux.cancelTimeout(_timeoutTask);
                _bayeux.removeServerSession(_session,false);
            }
        }

        public void onMessage(byte frame, String data)
        {
            boolean batch=false;
            try
            {
                WebSocketTransport.this._addresses.set(_addresses);
                _bayeux.setCurrentTransport(WebSocketTransport.this);
                
                ServerMessage.Mutable[] messages = ServerMessageImpl.parseMessages(data);

                for (ServerMessage.Mutable message : messages)
                {
                    boolean connect = Channel.META_CONNECT.equals(message.getChannel());
         
                    // Get the session from the message
                    if (_session==null)
                        _session=(ServerSessionImpl)_bayeux.getSession(message.getClientId());
                    
                    if (!batch && _session!=null && !connect)
                    {
                        // start a batch to group all resulting messages into a single response.
                        batch=true;
                        _session.startBatch();
                    }

                    // remember the connected status
                    boolean was_connected=_session!=null && _session.isConnected();

                    // handle the message
                    // the actual reply is return from the call, but other messages may
                    // also be queued on the session.
                    ServerMessage reply = _bayeux.handle(_session,message);

                    if (connect && reply.isSuccessful())
                    {
                        if (!_session.setDispatcher(this))
                            this.dispatch();
                        
                        long timeout=_session.getTimeout();
                        if (timeout<0) 
                            timeout=_timeout;
                        
                        if (_session.setDispatcher(this) && timeout>0 && was_connected)
                        {
                            // delay sending connect reply until dispatch or timeout.
                            _bayeux.startTimeout(_timeoutTask,timeout);
                            _connectReply=reply;
                            reply=null;
                        }
                        else if (!was_connected)
                        {
                            _session.startIntervalTimeout();
                        }   
                    }
                    
                    // send the reply (if not delayed)
                    if (reply!=null)
                    {
                        reply=_bayeux.extendReply(_session,reply);

                        if (batch)
                        {
                            _session.getQueue().add(reply);
                        }
                        else
                            send(reply);
                    }

                    // disassociate the reply
                    message.setAssociated(null);
                }
            }
            catch(IOException e)
            {
                _bayeux.getLogger().warn("",e);
            }
            finally
            {
                WebSocketTransport.this._addresses.set(null);
                _bayeux.setCurrentTransport(null);
                // if we started a batch - end it now
                if (batch)
                    _session.endBatch();
            }
        }

        public void onMessage(byte frame, byte[] data, int offset, int length)
        {
            try
            {
                onMessage(frame,new String(data,offset,length,"UTF-8"));
            }
            catch(UnsupportedEncodingException e)
            {
                Log.warn(e);
            }
        }

        public void cancelDispatch()
        {
        }

        public void dispatch()
        {
            while (_session!=null)
            {
                Queue<ServerMessage> queue = _session.getQueue();
                synchronized (queue)
                {
                    _session.dequeue();
                    if (_connectReply!=null)
                    {
                        queue.add(_bayeux.extendReply(_session,_connectReply));
                        _connectReply=null;
                        _session.startIntervalTimeout();
                    }
                    try
                    {
                        if (queue.size()>0)
                            send(queue);   
                    }
                    catch(IOException e)
                    {
                        _bayeux.getLogger().warn("io ",e);
                    }
                    queue.clear();
                }

                if (isMetaConnectDeliveryOnly() || _session.setDispatcher(this))
                    break;
            }
        }
        
        /* ------------------------------------------------------------ */
        protected void send(Queue<ServerMessage> messages) throws IOException
        {
            String data = JSON.toString(messages);
            _outbound.sendMessage(data);
        }
        
        /* ------------------------------------------------------------ */
        protected void send(ServerMessage message) throws IOException
        {
            String data = message.getJSON();
            _outbound.sendMessage("["+data+"]");
        }
    };
    
    
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.server.transports.HttpTransport#getCurrentLocalAddress()
     */
    @Override
    public InetSocketAddress getCurrentLocalAddress()
    {
        Addresses addresses = _addresses.get();
        if (addresses!=null)
            return addresses._local;
        return super.getCurrentLocalAddress();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.server.transports.HttpTransport#getCurrentRemoteAddress()
     */
    @Override
    public InetSocketAddress getCurrentRemoteAddress()
    {
        Addresses addresses = _addresses.get();
        if (addresses!=null)
            return addresses._remote;
        return super.getCurrentRemoteAddress();
    }

    private static class Addresses
    {
        InetSocketAddress _local;
        InetSocketAddress _remote;
    }
    
}
