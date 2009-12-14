package org.cometd.websocket.parser;

import java.util.Map;

import org.cometd.websocket.Message;

/**
 * @version $Revision$ $Date$
 */
public interface WebSocketParserListener
{
    void onHandshakeRequest(String path, Map<String, String> headers);

    void onHandshakeResponse(int code, Map<String, String> headers);

    public void onMessage(Message message);

    public interface Registration
    {
        void unregister();
    }

    public static class Adapter implements WebSocketParserListener
    {
        public void onHandshakeRequest(String path, Map<String, String> headers)
        {
        }

        public void onHandshakeResponse(int code, Map<String, String> headers)
        {
        }

        public void onMessage(Message message)
        {
        }
    }
}
