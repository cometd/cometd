package org.cometd.server.transport;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.text.ParseException;
import java.util.List;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;


public class WebSocketTransport extends HttpTransport
{
    public final static String PREFIX="ws";
    public final static String NAME="websocket";
    public final static String PROTOCOL_OPTION="protocol";
    public final static String BUFFER_SIZE_OPTION="bufferSize";

    private final WebSocketFactory _factory = new WebSocketFactory();
    private ThreadLocal<Addresses> _addresses = new ThreadLocal<Addresses>();

    private String _protocol="";

    public WebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux,NAME);
        setOptionPrefix(PREFIX);
        setOption(PROTOCOL_OPTION,_protocol);
        setOption(BUFFER_SIZE_OPTION,_factory.getBufferSize());
        setMetaConnectDeliveryOnly(false);
        setOption(META_CONNECT_DELIVERY_OPTION,isMetaConnectDeliveryOnly());
        setTimeout(15000);
        setOption(TIMEOUT_OPTION,getTimeout());
        setInterval(2500);
        setOption(INTERVAL_OPTION,getInterval());
        setMaxInterval(15000);
        setOption(MAX_INTERVAL_OPTION,getMaxInterval());
    }

    @Override
    public Set<String> getOptionNames()
    {
        Set<String> options = super.getOptionNames();
        options.add(PROTOCOL_OPTION);
        options.add(BUFFER_SIZE_OPTION);
        return options;
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
            response.sendError(400);
            return;
        }

        if (isMetaConnectDeliveryOnly())
        {
            Log.warn("MetaConnectDeliveryOnly not implemented for websocket");
            response.sendError(500);
            return;
        }

        Addresses addresses=new Addresses();
        addresses._local=new InetSocketAddress(request.getLocalAddr(),request.getLocalPort());
        addresses._remote=new InetSocketAddress(request.getRemoteAddr(),request.getRemotePort());

        WebSocket websocket = new WebSocketScheduler(addresses,request.getHeader("User-Agent"));
        _factory.upgrade(request,response,websocket,origin,protocol);
    }

    protected String checkOrigin(HttpServletRequest request, String host, String origin)
    {
        if (origin==null)
            origin=host;
        return origin;
    }

    protected class WebSocketScheduler implements WebSocket, AbstractServerTransport.Scheduler
    {
        protected final Addresses _addresses;
        protected final String _userAgent;
        protected ServerSessionImpl _session;
        protected Outbound _outbound;
        protected ServerMessage _connectReply;
        protected final Timeout.Task _timeoutTask = new Timeout.Task()
        {
            @Override
            public void expired()
            {
                // send the meta connect response after timeout.
                if (_session!=null)
                {
                    WebSocketScheduler.this.schedule();
                }
            }
        };

        public WebSocketScheduler(Addresses addresses,String userAgent)
        {
            _addresses=addresses;
            _userAgent=userAgent;
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
                getBayeux().cancelTimeout(_timeoutTask);
                getBayeux().removeServerSession(_session,false);
            }
        }

        public void onMessage(byte frame, String data)
        {
            boolean batch=false;
            try
            {
                WebSocketTransport.this._addresses.set(_addresses);
                getBayeux().setCurrentTransport(WebSocketTransport.this);

                ServerMessage.Mutable[] messages = ServerMessageImpl.parseMessages(data);

                for (ServerMessage.Mutable message : messages)
                {
                    boolean connect = Channel.META_CONNECT.equals(message.getChannel());

                    // Get the session from the message
                    String client_id=message.getClientId();
                    if (_session==null || client_id!=null && !client_id.equals(_session.getId()))
                        _session=(ServerSessionImpl)getBayeux().getSession(message.getClientId());
                    else if (!_session.isHandshook())
                    {
                        batch=false;
                        _session=null;
                    }

                    if (!batch && _session!=null && !connect && !message.isMeta())
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
                    ServerMessage reply = getBayeux().handle(_session,message);

                    if (connect && reply.isSuccessful())
                    {
                        _session.setUserAgent(_userAgent);
                        _session.setScheduler(this);

                        long timeout=_session.calculateTimeout(getTimeout());

                        if (timeout>0 && was_connected)
                        {
                            // delay sending connect reply until dispatch or timeout.
                            getBayeux().startTimeout(_timeoutTask,timeout);
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
                        reply=getBayeux().extendReply(_session,_session,reply);

                        if (batch)
                        {
                            _session.addQueue(reply);
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
                getBayeux().getLogger().warn("",e);
            }
            catch (ParseException e)
            {
                handleJSONParseException(e.getMessage(), e.getCause());
            }
            finally
            {
                WebSocketTransport.this._addresses.set(null);
                getBayeux().setCurrentTransport(null);
                // if we started a batch - end it now
                if (batch)
                    _session.endBatch();
            }
        }

        protected void handleJSONParseException(String json, Throwable exception)
        {
            getBayeux().getLogger().debug("Error parsing JSON: " + json, exception);
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
        }

        public void schedule()
        {
            // TODO should schedule another thread!
            // otherwise a receive can be blocked writing to another client

            final ServerSessionImpl session=_session;
            if (session!=null)
            {
                final List<ServerMessage> queue = session.takeQueue();

                if (_connectReply!=null)
                {
                    queue.add(getBayeux().extendReply(session,session,_connectReply));
                    _connectReply=null;
                    session.startIntervalTimeout();
                }
                try
                {
                    if (queue.size()>0)
                        send(queue);
                }
                catch(IOException e)
                {
                    getBayeux().getLogger().warn("io ",e);
                }
            }
        }

        /* ------------------------------------------------------------ */
        protected void send(List<ServerMessage> messages) throws IOException
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
     * @see org.cometd.server.transport.HttpTransport#getCurrentLocalAddress()
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
     * @see org.cometd.server.transport.HttpTransport#getCurrentRemoteAddress()
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
