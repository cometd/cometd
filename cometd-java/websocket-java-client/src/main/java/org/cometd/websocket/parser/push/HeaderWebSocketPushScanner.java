package org.cometd.websocket.parser.push;

import java.nio.ByteBuffer;

import org.cometd.websocket.WebSocketException;

/**
 * @version $Revision$ $Date$
 */
abstract class HeaderWebSocketPushScanner extends WebSocketPushScanner
{
    private State state = State.BEFORE_NAME;
    private final Token name = new Token();
    private final Token value = new Token();

    public boolean scan(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte currByte = buffer.get();
            switch (state)
            {
                case BEFORE_NAME:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new WebSocketException();
                    }
                    else
                    {
                        state = State.NAME;
                        name.append(currByte);
                    }
                    break;
                case NAME:
                    if (currByte == ':')
                    {
                        state = State.BEFORE_VALUE;
                    }
                    else if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new WebSocketException();
                    }
                    else
                    {
                        name.append(currByte);
                    }
                    break;
                case BEFORE_VALUE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new WebSocketException();
                    }
                    else
                    {
                        state = State.VALUE;
                        value.append(currByte);
                    }
                    break;
                case VALUE:
                    if (isLineSeparator(currByte))
                    {
                        state = State.AFTER_VALUE;
                    }
                    else
                    {
                        value.append(currByte);
                    }
                    break;
                case AFTER_VALUE:
                    onHeader(name.getByteBuffer(), value.getByteBuffer());
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                        {
                            if (currByte == buffer.get(buffer.position() - 2))
                            {
                                // We saw a EOL, here we see another and the 2 are equal, assume headers completed:
                                // Host: localhost:8080\n
                                // \n
                                return done(true);
                            }
                            else
                            {
                                // We saw 2 different EOL characters, assume it's just EOL and loop
                                state = State.EOL;
                            }
                        }
                        else
                        {
                            throw new WebSocketException();
                        }
                    }
                    else
                    {
                        reset();
                        // Unget the character and loop
                        buffer.position(buffer.position() - 1);
                    }
                    break;
                case EOL:
                    if (isLineSeparator(currByte))
                    {
                        if (currByte == buffer.get(buffer.position() - 2))
                        {
                            // We saw 3 EOLs, of which the last 2 are equal, assume headers completed:
                            // Host: localhost:8080\r\n
                            // \n
                            return done(true);
                        }
                        else
                        {
                            state = State.AFTER_EOL;
                        }
                    }
                    else
                    {
                        reset();
                        // Unget the character and loop
                        buffer.position(buffer.position() - 1);
                    }
                    break;
                case AFTER_EOL:
                    if (isLineSeparator(currByte))
                    {
                        // We saw the 4th EOL character, we are done
                        return done(true);
                    }
                    throw new WebSocketException();
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

    protected abstract void onHeader(ByteBuffer name, ByteBuffer value);

    private void reset()
    {
        name.reset();
        value.reset();
        state = State.BEFORE_NAME;
    }

    private enum State
    {
        BEFORE_NAME, NAME, BEFORE_VALUE, VALUE, AFTER_VALUE, EOL, AFTER_EOL
    }
}
