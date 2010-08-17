package org.cometd.examples;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;

/**
 *
 */
public class ConsoleChatClient
{
    public static void main(String[] args) throws IOException
    {
        ConsoleChatClient client = new ConsoleChatClient();
        client.run();
    }

    private volatile String nickname = "";
    private volatile BayeuxClient client;
    private final ChatListener chatListener = new ChatListener();
    private final MembersListener membersListener = new MembersListener();

    private void run() throws IOException
    {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));

        String defaultURL = "http://localhost:8080/cometd/cometd";
        System.err.printf("Enter Bayeux Server URL [%s]: ", defaultURL);

        String url = input.readLine();
        if (url == null)
            return;
        if (url.trim().length() == 0)
            url = defaultURL;

        while (nickname.trim().length() == 0)
        {
            System.err.printf("Enter nickname: ");
            nickname = input.readLine();
            if (nickname == null)
                return;
        }

        client = new BayeuxClient(url, LongPollingTransport.create(null));
        client.getChannel(Channel.META_HANDSHAKE).addListener(new InitializerListener());
        client.getChannel(Channel.META_CONNECT).addListener(new ConnectionListener());

        client.handshake();
        boolean success = client.waitFor(1000, BayeuxClient.State.CONNECTED);
        if (!success)
        {
            System.err.printf("Could not handshake with server at %s%n", url);
            return;
        }

        while (true)
        {
            String text = input.readLine();
            if (text == null || "\\q".equals(text))
            {
                Map<String, Object> data = new HashMap<String, Object>();
                data.put("user", nickname);
                data.put("membership", "leave");
                data.put("chat", nickname + " has left");
                client.getChannel("/chat/demo").publish(data);
                client.disconnect();
                client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
                break;
            }

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("user", nickname);
            data.put("chat", text);
            client.getChannel("/chat/demo").publish(data);
        }
    }

    private void initialize()
    {
        client.batch(new Runnable()
        {
            public void run()
            {
                ClientSessionChannel chatChannel = client.getChannel("/chat/demo");
                chatChannel.unsubscribe(chatListener);
                chatChannel.subscribe(chatListener);

                ClientSessionChannel membersChannel = client.getChannel("/chat/members");
                membersChannel.unsubscribe(membersListener);
                membersChannel.subscribe(membersListener);

                Map<String, Object> data = new HashMap<String, Object>();
                data.put("user", nickname);
                data.put("membership", "join");
                data.put("chat", nickname + " has joined");
                chatChannel.publish(data);
            }
        });
    }

    private void connectionEstablished()
    {
        System.err.printf("system: Connection to Server Opened%n");
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("user", nickname);
        data.put("room", "/chat/demo");
        client.getChannel("/service/members").publish(data);
    }

    private void connectionClosed()
    {
        System.err.printf("system: Connection to Server Closed%n");
    }

    private void connectionBroken()
    {
        System.err.printf("system: Connection to Server Broken%n");
    }

    private class InitializerListener implements ClientSessionChannel.MessageListener
    {
        public void onMessage(ClientSessionChannel channel, Message message)
        {
            if (message.isSuccessful())
            {
                initialize();
            }
        }
    }

    private class ConnectionListener implements ClientSessionChannel.MessageListener
    {
        private boolean wasConnected;
        private boolean connected;

        public void onMessage(ClientSessionChannel channel, Message message)
        {
            if (client.isDisconnected())
            {
                connected = false;
                connectionClosed();
                return;
            }

            wasConnected = connected;
            connected = message.isSuccessful();
            if (!wasConnected && connected)
            {
                connectionEstablished();
            }
            else if (wasConnected && !connected)
            {
                connectionBroken();
            }
        }
    }

    private class ChatListener implements ClientSessionChannel.MessageListener
    {
        public void onMessage(ClientSessionChannel channel, Message message)
        {
            Map<String, Object> data = message.getDataAsMap();
            String fromUser = (String)data.get("user");
            String text = (String)data.get("chat");
            System.err.printf("%s: %s%n", fromUser, text);
        }
    }

    private class MembersListener implements ClientSessionChannel.MessageListener
    {
        public void onMessage(ClientSessionChannel channel, Message message)
        {
            Object[] members = (Object[])message.getData();
            System.err.printf("Members: %s%n", Arrays.asList(members));
        }
    }
}
