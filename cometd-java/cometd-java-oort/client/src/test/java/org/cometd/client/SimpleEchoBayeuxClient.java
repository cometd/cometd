package org.cometd.client;

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.Map;
import java.util.Timer;

import org.cometd.Client;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.server.AbstractBayeux;
import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * SimpleEchoBayeuxClient
 *
 * A rather silly java client for Bayeux that prints out messages
 * received on a channel. As the client interactively prompts the
 * user for input to send on the channel, the effect is to echo
 * back the messages.
 */
public class SimpleEchoBayeuxClient
{
    public static long _id = 0;
    String _who;
    BayeuxClient _client;
    Timer _timer;
    HttpClient _httpClient;
    boolean _connected;
    
    public SimpleEchoBayeuxClient(String host, int port, String uri, String who)
    throws Exception
    {  
        _who = who;
        if (_who == null)
            _who = "anonymous";
        _timer = new Timer("SharedBayeuxClientTimer", true); 
        _httpClient = new HttpClient();
 
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setMaxConnectionsPerAddress(40000);

        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setMaxThreads(500);
        pool.setMinThreads(200);
        pool.setDaemon(true);
        _httpClient.setThreadPool(pool);
        _httpClient.start();

        Address address = new Address (host,port);
        
        
        _client = new BayeuxClient(_httpClient,address,uri,_timer)
        {
            public void metaConnect(boolean success,Message message)
            {
                super.metaConnect(success,message);
                
                if (success && !_connected) 
                {
                    System.err.println("Reconnected!");
                }
                else if (!success && _connected)
                {
                    System.err.println("Server disconnected");
                }
                _connected = success;
            }

            public void metaHandshake(boolean success, boolean reestablish,Message message)
            {
                super.metaHandshake(success,reestablish,message);

                if (success && reestablish)
                {
                    _client.subscribe("/foo/alpha");
                    System.err.println("Resubscribed");
                }
            }

            public void metaPublishFail(Throwable th,Message[] messages)
            {
                super.metaPublishFail(th,messages);
            }  
        };
        
        _client.addListener (new MessageListener ()
        {

            public void deliver(Client fromClient, Client toClient, Message msg)
            {
                if (msg == null)
                    return;
                if ("/foo/alpha".equals(msg.getChannel()))
                {  
                    Object data=(Object)msg.get(AbstractBayeux.DATA_FIELD);

                    String user = "unknown";
                    if (data!=null)
                    {
                        user = (String)((Map)data).get("user");
                        String chat = (String)((Map)data).get("chat");

                        if (user == null) 
                            user = "unknown";

                        if (user.equals(_who))
                            user = "I";

                        System.err.println("\n\t"+user+" said: "+chat);
                    }
                }
            }
        });
        
       
      
    }
    
    public void start () throws Exception
    {
        _client.start(); 
        _client.subscribe("/foo/alpha"); 
        Object msg=new JSON.Literal("{\"user\":\""+_who+"\",\"chat\":\"Has joined\"}");
        _client.publish("/foo/alpha", msg, String.valueOf(_id++));
        _connected = true;
    }
    
    public void stop () throws Exception
    { 
        _client.stop();
        _connected = false;
    }
    
    public void publish (String say)
    {
        Object msg=new JSON.Literal("{\"user\":\""+_who+"\",\"chat\":\""+say+"\"}");
        _client.publish("/foo/alpha", msg, String.valueOf(_id++));
    }
    
    public void stopHttp()
    {
        try
        {
            System.err.println("Stopping HttpClient");
            _httpClient.stop(); 
            System.err.println("Stopped HttpClient");
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
    public void startHttp()
    {
        try
        {
            System.err.println("Starting HttpClient");
            _httpClient.start();
            System.err.println("Started HttpClient");
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
    
    public static final void main(String[] args)
    {
        try
        {
            //arg0: server url
            String serverCometdUrl = (args.length > 0? args[0] : "/cometd/cometd");
            
            //arg1: server port
            int serverPort = (args.length >= 2 ? Integer.valueOf(args[1]).intValue() : 8080);
            
            //arg2: username (not necessary)
            String user = (args.length >= 3 ? args[2] : "anonymous");
            
            SimpleEchoBayeuxClient sbc = new SimpleEchoBayeuxClient("localhost", serverPort, serverCometdUrl, user);
            sbc.start();
            LineNumberReader in = new LineNumberReader(new InputStreamReader(System.in));
            while (true)
            {
                try
                {
                    System.err.print("Enter something to say > ");
                    String say = in.readLine().trim();

                    /*
                     * Only here for testing
                     */
                    if (say.equalsIgnoreCase("stop"))
                        sbc.stop();
                    else if (say.equalsIgnoreCase("start"))
                        sbc.start();
                    else if (say.equalsIgnoreCase("stophttp"))
                        sbc.stopHttp();
                    else if (say.equalsIgnoreCase("starthttp"))
                        sbc.startHttp();
                    else
                        sbc.publish (say);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
