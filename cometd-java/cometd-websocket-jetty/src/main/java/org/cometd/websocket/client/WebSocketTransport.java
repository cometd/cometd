package org.cometd.websocket.client;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.TransportListener;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.eclipse.jetty.websocket.WebSocketClient;

public class WebSocketTransport extends HttpClientTransport
{
    public final static String PREFIX = "ws";
    public final static String NAME = "websocket";
    public final static String PROTOCOL_OPTION = "protocol";
    public final static String BUFFER_SIZE_OPTION = "bufferSize";

    public static WebSocketTransport create(Map<String, Object> options)
    {
        WebSocketClient webSocketClient = new WebSocketClient();
        webSocketClient.setBufferSize(getOption(BUFFER_SIZE_OPTION,options,PREFIX,webSocketClient.getBufferSize()).intValue());
        return create(options, webSocketClient);
    }

    public static WebSocketTransport create(Map<String, Object> options, WebSocketClient websocketClient)
    {
        WebSocketTransport transport = new WebSocketTransport(options, websocketClient);
        if (!websocketClient.isStarted())
        {
            try
            {
                websocketClient.start();
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        }
        return transport;
    }

    private final WebSocketClient _webSocketClient;
    private final WebSocket _websocket = new CometDWebSocket();
    private WebSocket.Connection _connection;
    private String _protocol="cometd";
    private volatile TransportListener _listener;
    private volatile Map<String, Object> _advice;


    protected WebSocketTransport(Map<String, Object> options, WebSocketClient client)
    {
        super(NAME,options);
        _webSocketClient=client;
        setOptionPrefix(PREFIX);
    }

    public boolean accept(String version)
    {
        return true;
    }

    @Override
    public void init()
    {
        super.init();

        _protocol=getOption(PROTOCOL_OPTION,_protocol);
        int maxIdleTime=
            getOption(TIMEOUT_OPTION,30000)+
            getOption(INTERVAL_OPTION,10000)+
            getOption(MAX_NETWORK_DELAY_OPTION,5000)*2;

        Map<String,String> cookies = new HashMap<String,String>();
        for (Cookie cookie : getCookieProvider().getCookies())
            cookies.put(cookie.getName(),cookie.getValue());

        try
        {
            URI uri=new URI(getURL());
            _webSocketClient.open(uri,_websocket,_protocol,maxIdleTime,cookies,null);
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void abort()
    {
        // TODO Auto-generated method stub

        System.err.println("abort ");
    }

    @Override
    public void reset()
    {
        // TODO Auto-generated method stub

        System.err.println("reset ");
        final Connection connection;
        synchronized (WebSocketTransport.this)
        {
            connection=_connection;
        }
        if (connection!=null)
            connection.disconnect();
    }

    @Override
    public void send(TransportListener listener, Mutable... messages)
    {
        _listener=listener;

        final Connection connection;

        synchronized (WebSocketTransport.this)
        {
            try
            {
                if (_connection==null)
                    WebSocketTransport.this.wait(_webSocketClient.getConnectTimeout());
            }
            catch(InterruptedException e)
            {
                Log.ignore(e);
            }

            if (_connection==null)
            {
                listener.onConnectException(new Throwable(),messages);
                return;
            }
            connection=_connection;
        }



        String content = JSON.toString(messages);
        System.err.println("send "+content);
        try
        {
            connection.sendMessage(content);
            listener.onSending(messages);
        }
        catch (Exception x)
        {
            x.printStackTrace();
            listener.onException(x, messages);
        }
    }

    protected class CometDWebSocket implements WebSocket.OnTextMessage
    {
        public void onOpen(Connection connection)
        {
            System.err.println("onOpen "+connection);
            synchronized (WebSocketTransport.this)
            {
                WebSocketTransport.this._connection=connection;
                WebSocketTransport.this.notifyAll();
            }
        }

        public void onClose(int closeCode, String message)
        {
            System.err.println("onClose "+closeCode+" "+message);
            synchronized (WebSocketTransport.this)
            {
                WebSocketTransport.this._connection=null;
            }
            _connection=null;

            // TODO Surely more to do here?
        }

        public void onError(String message, Throwable ex)
        {
        }

        public void onMessage(String data)
        {
            System.err.println("mesg "+data);
            List<Message.Mutable> messages = parseMessages(data);
            for (Message.Mutable message : messages)
            {
                if (message.isSuccessful() && Channel.META_CONNECT.equals(message.getChannel()))
                {
                    Map<String, Object> advice = message.getAdvice();
                    if (advice != null && advice.get("timeout") != null)
                        _advice = advice;
                }
            }
            _listener.onMessages(messages);

            // TODO
        }
    }

}
