package org.cometd.websocket.parser.push;

import java.nio.ByteBuffer;

import org.cometd.websocket.WebSocketException;

/**
 * @version $Revision$ $Date$
 */
abstract class ResponseLineWebSocketPushScanner extends WebSocketPushScanner
{
    private State state = State.BEFORE_VERSION;
    private final Token version = new Token();
    private final Token code = new Token();
    private final Token message = new Token();

    @Override
    public boolean scan(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte currByte = buffer.get();
            switch (state)
            {
                case BEFORE_VERSION:
                    // Eat whitespace between responses
                    if (!isWhitespace(currByte))
                    {
                        state = State.VERSION;
                        version.append(currByte);
                    }
                    break;
                case VERSION:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new WebSocketException();
                        state = State.BEFORE_CODE;
                    }
                    else
                    {
                        version.append(currByte);
                    }
                    break;
                case BEFORE_CODE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new WebSocketException();
                    }
                    else
                    {
                        state = State.CODE;
                        code.append(currByte);
                    }
                    break;
                case CODE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new WebSocketException();
                        state = State.BEFORE_MESSAGE;
                    }
                    else
                    {
                        code.append(currByte);
                    }
                    break;
                case BEFORE_MESSAGE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new WebSocketException();
                    }
                    else
                    {
                        state = State.MESSAGE;
                        message.append(currByte);
                    }
                    break;
                case MESSAGE:
                    if (isLineSeparator(currByte))
                        state = State.AFTER_MESSAGE;
                    else
                        message.append(currByte);
                    break;
                case AFTER_MESSAGE:
                    onResponseLine(version.getByteBuffer(), code.getByteBuffer(), message.getByteBuffer());
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                        {
                            if (currByte != buffer.get(buffer.position() - 2))
                                // We saw 2 different EOL chars, we are done
                                return done(true);
                        }
                        else
                        {
                            throw new WebSocketException();
                        }
                    }
                    // Unget the char to be processed by other scanners
                    buffer.position(buffer.position() - 1);
                    return done(true);
                default:
                    throw new WebSocketException();
            }
        }
        return done(false);
    }

    private boolean done(boolean result)
    {
        if (result)
            reset();
        return result;
    }

    private void reset()
    {
        version.reset();
        code.reset();
        message.reset();
        state = State.BEFORE_VERSION;
    }

    private enum State
    {
        BEFORE_VERSION, VERSION, BEFORE_CODE, CODE, BEFORE_MESSAGE, MESSAGE, AFTER_MESSAGE
    }

    protected abstract void onResponseLine(ByteBuffer version, ByteBuffer code, ByteBuffer message);
}
