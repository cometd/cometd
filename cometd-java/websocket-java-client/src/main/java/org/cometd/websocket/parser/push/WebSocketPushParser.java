package org.cometd.websocket.parser.push;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.cometd.websocket.Message;
import org.cometd.websocket.MessageType;
import org.cometd.websocket.parser.WebSocketParser;
import org.cometd.websocket.parser.WebSocketParserListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketPushParser implements WebSocketParser
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final List<WebSocketParserListener> listeners = new ArrayList<WebSocketParserListener>();
    private final WebSocketPushScanner messageScanner = new MessageWebSocketPushScanner()
    {
        @Override
        protected void onMessage(MessageType type, ByteBuffer buffer)
        {
            message = type.messageFrom(buffer.array());
        }
    };
    private Message message;

    public void addListener(final WebSocketParserListener listener)
    {
        listeners.add(listener);
    }

    public void removeListener(WebSocketParserListener listener)
    {
        listeners.remove(listener);
    }

    protected List<WebSocketParserListener> getListeners()
    {
        return listeners;
    }

    protected void reset()
    {
    }

    public void parse(ByteBuffer buffer)
    {
        if (messageScanner.scan(buffer))
        {
            notifyOnMessage(message);
        }
    }

    private void notifyOnMessage(Message message)
    {
        for (WebSocketParserListener listener : getListeners())
        {
            try
            {
                listener.onMessage(message);
            }
            catch (Throwable x)
            {
                logger.debug("Exception while calling listener " + listener, x);
            }
        }
    }
}
