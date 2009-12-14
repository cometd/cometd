package org.cometd.websocket.parser.push;

import java.nio.ByteBuffer;

import org.cometd.websocket.MessageType;
import org.cometd.websocket.TextMessage;
import org.cometd.websocket.WebSocketException;

/**
 * @version $Revision$ $Date$
 */
abstract class MessageWebSocketPushScanner extends WebSocketPushScanner
{
    private State state = State.FRAME_TYPE;
    private MessageType messageType;
    private final Token text = new Token();

    public boolean scan(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte currByte = buffer.get();
            switch (state)
            {
                case FRAME_TYPE:
                    messageType = MessageType.from(currByte);
                    switch (messageType)
                    {
                        case TEXT:
                            state = State.TEXT;
                            break;
                        // TODO
//                        case BINARY:
//                            break;
                        default:
                            throw new WebSocketException();
                    }
                    break;
                case TEXT:
                    if (currByte == TextMessage.END)
                    {
                        onMessage(messageType, text.getByteBuffer());
                        reset();
                        return true;
                    }
                    else
                    {
                        text.append(currByte);
                    }
                    break;
                default:
                    throw new WebSocketException();

            }
        }
        return false;
    }

    private void reset()
    {
        state = State.FRAME_TYPE;
        messageType = null;
        text.reset();
    }

    protected abstract void onMessage(MessageType type, ByteBuffer buffer);

    private enum State
    {
        FRAME_TYPE, TEXT
    }
}
