//========================================================================
//Copyright 2007 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package org.cometd.client;

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.HashMap;
import java.util.Map;

import org.cometd.Client;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.client.ext.AckExtension;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class ChatRoomClient extends AbstractLifeCycle
{
    
    private HttpClient _httpClient;
    private BayeuxClient _bayeuxClient;
    private QueuedThreadPool _threadPool;
    
    private String _host;
    private int _port;
    private String _uri;
    private String _metaChannel;
    private String _publicChannel;
    private String _privateChannel;    
    private String _username;    

    private boolean _connected = false;
    private boolean _sendDisconnectMsg = !"false".equals("chatroomclient.send_disconnect_msg");
    
    public ChatRoomClient()
    {
        this(System.getProperty("chatroom.host", "localhost"), 
                Integer.parseInt(System.getProperty("chatroom.port", "8080")), 
                System.getProperty("chatroom.uri", "/cometd/cometd"), 
                System.getProperty("chatroom.publicChannel", "/chat/demo"),
                System.getProperty("chatroom.privateChannel", "/service/privatechat"),
                System.getProperty("chatroom.metaChannel", "/cometd/meta"));
    }
    
    public ChatRoomClient(int port)
    {
        this(System.getProperty("chatroom.host", "localhost"), 
                port, 
                System.getProperty("chatroom.uri", "/cometd/cometd"), 
                System.getProperty("chatroom.publicChannel", "/chat/demo"),
                System.getProperty("chatroom.privateChannel", "/service/privatechat"),
                System.getProperty("chatroom.metaChannel", "/cometd/meta"));
    }
    
    public ChatRoomClient(String host, int port, String uri, String channel, 
            String privateChannel, String metaChannel)
    {
        _host = host;
        _port = port;
        _uri = uri;
        _publicChannel = channel;
        _privateChannel = privateChannel;
        _metaChannel = metaChannel;
    }
    
    public String getMetaChannel()
    {
        return _metaChannel;
    }
    
    public String getPublicChannel()
    {
        return _publicChannel;
    }
    
    public boolean isConnected()
    {
        return _connected;
    }
    
    public void setHost(String host)
    {
        _host = host;
    }
    
    public void setPort(int port)
    {
        _port = port;
    }
    
    public void setUri(String uri)
    {
        _uri = uri;
    }
    
    public String getUsername()
    {
        return _username;
    }
    
    public HttpClient getHttpClient()
    {
        return _httpClient;
    }
    
    protected void doStart() throws Exception
    {        
        Log.info("{} {}", getClass().getSimpleName(), "starting chat client.");
        
        if(_threadPool==null)
        {
            _threadPool = new QueuedThreadPool();
            _threadPool.setMaxThreads(16);
            _threadPool.setDaemon(true);
            _threadPool.setName(getClass().getSimpleName());
            _threadPool.start();
        }        
        
        
        if(_httpClient==null)
        {
            _httpClient = new HttpClient();        
            _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);        
            _httpClient.setMaxConnectionsPerAddress(5);
            _httpClient.setThreadPool(_threadPool);
            _httpClient.start();
        }        
        
        
        Log.info("{} {}", getClass().getSimpleName(), "http client started.");
        if(_bayeuxClient==null)
        {
        	String url = "http://localhost:" + _port + "/cometd";
            _bayeuxClient = new BayeuxClient(_httpClient, url);
            _bayeuxClient.addExtension(new AckExtension());
            _bayeuxClient.addListener(new ChatListener());
        }
        
        // start asyncrhonously due to long socket timeout
        // workaround for the android jvm bug when the endpoint doesnt exist        
        _threadPool.dispatch(new Runnable()
        {
            public void run()
            {
                try
                {
                    _bayeuxClient.start();
                } 
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
                Log.info("{} {}", getClass().getSimpleName(), "bayeux client started.");
            }
        });
        
        Log.info("{} {}", getClass().getSimpleName(), "chat client started.");
    }
    
    protected void doStop() throws Exception
    {
        Log.info("{} {}", getClass().getSimpleName(), "stopping chat client.");
        
        if(_connected && _username!=null)
        {
            Log.info("{} {}", getClass().getSimpleName(), "leaving chat room.");
            //leave();
            Log.info("{} {}", getClass().getSimpleName(), "removing client from chat room.");
            _bayeuxClient.disconnect();
        }        
     
        Log.info("{} {}", getClass().getSimpleName(), "chat client stopped.");
    }
    
    public void onMessageReceived(Client from, Map<String,Object> message)
    {
        if("private".equals(message.get("scope")))
            onPrivateMessageReceived(from, message);
        else
            onPublicMessageReceived(from, message);
    }
    
    public void onPublicMessageReceived(Client from, Map<String,Object> message)
    {

    }
    
    public void onPrivateMessageReceived(Client from, Map<String,Object> message)
    {

    }
    
    public void onUserListRefreshed(Object[] users)
    {
     
    }
    
    
    public boolean join(String username) throws Exception
    {
        if(_username!=null)
            return false;      
        
        Log.info("{} {}", getClass().getSimpleName(), "joining channel: " + _publicChannel + " with " + username);
        
        _bayeuxClient.startBatch();
        
        _bayeuxClient.subscribe(_publicChannel);

        _bayeuxClient.publish(_publicChannel, 
                new Msg().add("user", username)
                .add("join", Boolean.TRUE)
                .add("chat", username + " has joined"), 
                String.valueOf(System.currentTimeMillis()));
                
        _bayeuxClient.endBatch();
        _username = username;
        return true;
    }
    
    public void connectOrDisconnect()
    {
        try
        {
            if(_httpClient.isStarted())
            {
                System.err.println("stopping");
                _httpClient.stop();
                Thread.sleep(500);
                System.err.println("stopped");
            }
            else
            {
                //String u = _username;
                //_username = null;
                System.err.println("restarting");
                _httpClient.start();
                Thread.sleep(500);
                //join(u);
            }
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    public boolean leave() throws Exception
    {
        if(_username==null)
            return false;
        
        Log.info("{} {}", getClass().getSimpleName(), "leaving channel: " + _publicChannel + " with " + _username);
        
        _bayeuxClient.startBatch();
        
        _bayeuxClient.unsubscribe(_publicChannel);

        _bayeuxClient.publish(_publicChannel, 
                new Msg().add("user", _username)
                .add("leave", Boolean.TRUE)
                .add("chat", _username + " has left"), 
                String.valueOf(System.currentTimeMillis()));        
                
        _bayeuxClient.endBatch();
        _username = null;
        return true;
    }
    
    public boolean chat(String message)
    {
        if(_username==null)
            return false;
        
        _bayeuxClient.publish(_publicChannel, 
                new Msg().add("user", _username)                
                .add("chat", message), 
                String.valueOf(System.currentTimeMillis()));
        
        return true;
    }
    
    public boolean chat(String message, String user)
    {
        if(_username==null)
            return false;
        if(user==null)
            return chat(message);
        
        _bayeuxClient.publish(_privateChannel, 
                new Msg().add("user", _username)
                .add("room", _publicChannel)
                .add("chat", message)
                .add("peer", user), 
                null);
        
        return true;
    }    
    
    class ChatListener implements MessageListener
    {

        public void deliver(Client from, Client to, Message message)
        {
            if(!_connected)
            {
                _connected = true;
                synchronized(this)
                {
                    this.notify();
                }
            }
            Object data = message.getData();
            if(data==null)
                return;
            
            if(data.getClass().isArray())                           
                onUserListRefreshed((Object[])data);            
            else if(data instanceof Map)
                onMessageReceived(from, (Map<String,Object>)data);
            
        }        
    }
    
    public static class Msg extends HashMap<String, Object>
    {
        
        Msg add(String name, Object value)
        {
            put(name, value);
            return this;
        }
        
    }    
    
    public static void main(String[] args) throws Exception
    {
        ChatRoomClient room = new ChatRoomClient()
        {
            public void onUserListRefreshed(Object[] users)
            {
                for(Object u : users)
                    Log.info("user: {}", u);
            }
            
            public void onPublicMessageReceived(org.cometd.Client from, Map<String,Object> message)
            {
                Log.info("public message: {}", message);
            }
            
            public void onPrivateMessageReceived(org.cometd.Client from, Map<String,Object> message)
            {
                Log.info("private message: {}", message);
            }
        };
        room._sendDisconnectMsg = false;
        room.start();
        
        Thread.sleep(500);
        
        room.join("foo" + System.currentTimeMillis());             
        
        LineNumberReader in = new LineNumberReader(new InputStreamReader(System.in));
        
        for(;;)
        {
            System.err.print("enter chat message: ");
            String message = in.readLine().trim();
            int idx = message.indexOf("::");
            if(idx==-1)
                room.chat(message);
            else if(idx==0)
            {
                // connect/disconnect ... to check the AckExtension
                room.connectOrDisconnect();
            }
            else
                room.chat(message.substring(idx+1), message.substring(0, idx));

        }
        
    }

}
