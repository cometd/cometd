package org.cometd.bwtp.parser.push;

import java.nio.ByteBuffer;

import org.cometd.bwtp.parser.ParseException;

/**
 * @version $Revision: 849 $ $Date$
 */
public abstract class HeadersBWTPPushScanner extends BWTPPushScanner
{
    private final Token name = new Token();
    private final Token value = new Token();
    private State state = State.BEFORE_NAME;
    private int count;
    private byte firstEOL;
    private byte secondEOL;

    @Override
    public boolean scan(ByteBuffer buffer, int limit)
    {
        while (buffer.hasRemaining())
        {
            byte currByte = buffer.get();
            ++count;
            switch (state)
            {
                case BEFORE_NAME:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            // No headers
                            return done(true);
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
                            throw new ParseException();
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
                            throw new ParseException();
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
                        firstEOL = currByte;
                        onHeader(utf8Decode(name.getByteBuffer()), utf8Decode(value.getByteBuffer()));
                        if (count == limit)
                            return done(true);
                    }
                    else
                    {
                        value.append(currByte);
                    }
                    break;
                case AFTER_VALUE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                        {
                            if (currByte == firstEOL)
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
                                secondEOL = currByte;
                                if (count == limit)
                                    return done(true);
                            }
                        }
                        else
                        {
                            throw new ParseException();
                        }
                    }
                    else
                    {
                        reset();
                        // Push back the character and loop
                        buffer.position(buffer.position() - 1);
                        --count;
                    }
                    break;
                case EOL:
                    if (isLineSeparator(currByte))
                    {
                        if (currByte == secondEOL)
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
                        // Push back the character and loop
                        buffer.position(buffer.position() - 1);
                        --count;
                    }
                    break;
                case AFTER_EOL:
                    if (isLineSeparator(currByte))
                    {
                        // We saw the 4th EOL character, we are done
                        return done(true);
                    }
                    throw new ParseException();
                default:
                    throw new ParseException();
            }
        }
        return done(false);
    }

    private boolean done(boolean result)
    {
        if (result)
        {
            reset();
            count = 0;
        }
        return result;
    }

    protected abstract void onHeader(String name, String value);

    private void reset()
    {
        name.reset();
        value.reset();
        state = State.BEFORE_NAME;
        firstEOL = 0;
        secondEOL = 0;
    }

    private enum State
    {
        BEFORE_NAME, NAME, BEFORE_VALUE, VALUE, AFTER_VALUE, EOL, AFTER_EOL
    }
}
