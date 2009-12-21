package org.cometd.bayeux.client.transport;

import org.cometd.bayeux.client.MetaMessage;
import org.cometd.websocket.Message;
import org.cometd.websocket.TextMessage;
import org.cometd.websocket.client.Client;
import org.cometd.websocket.client.Listener;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketTransport extends AbstractTransport
{
    private final Client client;
    private volatile boolean accept = true;

    public WebSocketTransport(Client client)
    {
        this.client = client;
        this.client.addListener(new MessageListener());
    }

    public String getType()
    {
        return "websocket";
    }

    public boolean accept(String bayeuxVersion)
    {
        return accept;
    }

    public void init()
    {
        accept = client.open();
    }

    public void send(MetaMessage... messages)
    {
        String content = JSON.toString(messages);
        Message message = new TextMessage(content);
        client.send(message);
    }

    private class MessageListener extends Listener.Adapter
    {
        @Override
        public void onMessage(Message message)
        {
            // TODO: explicit cast to TextMessage
            TextMessage textMessage = (TextMessage)message;
            String text = textMessage.getText();
            Object content = JSON.parse(text);
            // TODO: convert from map to MetaMessage
            notifyMetaMessages((MetaMessage[])content);
        }
    }
}
