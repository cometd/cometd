package org.cometd.server.transports;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Queue;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ServerTransport;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;



public class WebSocketsTransport extends HttpTransport
{
    public final static String NAME="websockets";
    public final static String PROTOCOL_OPTION="protocol";
    public final static String BUFFER_SIZE_OPTION="bufferSize";
    
    private final WebSocketFactory _factory = new WebSocketFactory();
    
    private String _protocol="bayeux";
    
    public WebSocketsTransport(BayeuxServerImpl bayeux, Map<String,Object> options)
    {
        super(bayeux,NAME,options);
        _prefix.add("ws");
        setOption(PROTOCOL_OPTION,_protocol);
        setOption(BUFFER_SIZE_OPTION,_factory.getBufferSize());
        setOption(META_CONNECT_DELIVERY_OPTION,_metaConnectDeliveryOnly);
    }

    @Override
    public void init()
    {
        _protocol=getOption(PROTOCOL_OPTION,_protocol);
        _factory.setBufferSize(getOption(BUFFER_SIZE_OPTION,_factory.getBufferSize()));
    }
    
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        String protocol=request.getHeader("WebSocket-Protocol");

        String host=request.getHeader("Host");
        String origin=request.getHeader("Origin");
        origin=checkOrigin(request,host,origin);
        
        if (origin==null || !_protocol.equals(protocol))
        {
            response.sendError(403);
            return;
        }
        
        WebSocket websocket = new BayeuxWebSocket();
        
        _factory.upgrade(request,response,websocket,origin,protocol);
    }

    protected String checkOrigin(HttpServletRequest request, String host, String origin)
    {
        if (origin==null)
            origin=host;
        return origin;
    }
    
    protected class BayeuxWebSocket extends Timeout.Task implements WebSocket, ServerTransport.Dispatcher
    {
        protected ServerSessionImpl _session;
        protected Outbound _outbound;
        protected ServerMessage _connectReply;
        
        public void onConnect(Outbound outbound)
        {
            _outbound = outbound;
        }

        public void onDisconnect()
        {
            // TODO do we allow multiple connections?
            // _bayeux.removeServerSession(_session,false);
        }

        public void onMessage(byte frame, String data)
        {
            boolean batch=false;
            ServerMessage.Mutable message = null;
            try
            {
                message = _bayeux.getServerMessagePool().parseMessage(data);
                
                // reference it (this should make ref=1)
                message.incRef();
                
                // Get the session from the message
                if (_session==null)
                    _session=(ServerSessionImpl)_bayeux.getSession(message.getClientId());
                if (_session!=null && !message.isMeta())
                {
                    // start a batch to group all resulting messages into a single response.
                    batch=true;
                    _session.startBatch();
                }
                
                // remember the connected status
                boolean was_connected=_session!=null && _session.isConnected();
                boolean connect = Channel.META_CONNECT.equals(message.getChannelId());

                // handle the message
                // the actual reply is return from the call, but other messages may
                // also be queued on the session.
                ServerMessage reply = _bayeux.handle(_session,message);

                // Do we have a reply
                if (reply!=null)
                {
                    if (_session==null)
                        // This must be a handshake
                        // extract a session from the reply (if we don't already know it
                        _session=(ServerSessionImpl)_bayeux.getSession(reply.getClientId());
                    else if (connect && reply.isSuccessful())
                    {
                        // If this is a connect or we can send messages with any response 
                        if (isMetaConnectDeliveryOnly())
                        {
                            _connectReply=reply;
                            reply=null;
                            _bayeux.startTimeout(this,_session.getTimeout());
                        }
                        if (!was_connected || !_session.setDispatcher(this))
                            dispatch();
                    }
                    
                    reply=_bayeux.extendReply(_session,reply);
                    if (reply!=null)
                        send(reply);
                }
                
                // disassociate the reply
                message.setAssociated(null);
                // dec our own ref, this should be to 0 unless message was ref'd elsewhere.
                message.decRef();

            }
            catch(IOException e)
            {
                _bayeux.getLogger().warn(""+message,e);
            }
            finally
            {
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

        public void cancel()
        {
            _outbound.disconnect();
        }

        public void dispatch()
        {
            if (_session!=null)
            {
                Queue<ServerMessage> queue = _session.getQueue();
                synchronized (queue)
                {
                    _session.dequeue();
                    try
                    {
                        for (int i=queue.size();i-->0;)
                        {
                            ServerMessage m=queue.poll();
                            send(m);
                        }

                        if (_connectReply!=null)
                        {
                            ServerMessage reply=_bayeux.extendReply(_session,_connectReply);
                            if (reply!=null)
                                send(reply);
                            _connectReply.decRef();
                            _connectReply=null;
                            _session.startIntervalTimeout();
                        }
                    }
                    catch(IOException e)
                    {
                        _bayeux.getLogger().warn("dispatch ",e);
                    }
                }
            }
            
            if (_session!=null && isMetaConnectDeliveryOnly())
                _session.setDispatcher(this);
        }
        
        /* ------------------------------------------------------------ */
        @Override
        protected void expired()
        {
            if (_session!=null && _session.setDispatcher(null))
                dispatch();
        }

        protected void send(ServerMessage message) throws IOException
        {
            _outbound.sendMessage(WebSocket.SENTINEL_FRAME,message.getJSON());
        }
    };
}
