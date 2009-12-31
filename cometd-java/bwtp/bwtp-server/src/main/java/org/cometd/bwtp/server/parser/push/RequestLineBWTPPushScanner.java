package org.cometd.bwtp.server.parser.push;

import java.nio.ByteBuffer;

import org.cometd.bwtp.parser.ParseException;
import org.cometd.bwtp.parser.push.BWTPPushScanner;
import org.cometd.bwtp.parser.push.Token;

/**
 * @version $Revision$ $Date$
 */
public abstract class RequestLineBWTPPushScanner extends BWTPPushScanner
{
    private final Token method = new Token();
    private final Token uri = new Token();
    private final Token version = new Token();
    private State state = State.BEFORE_METHOD;
    private byte firstEOL;

    @Override
    public boolean scan(ByteBuffer buffer, int limit)
    {
        while (buffer.hasRemaining())
        {
            byte currByte = buffer.get();
            switch (state)
            {
                case BEFORE_METHOD:
                    // Eat whitespace
                    if (!isWhitespace(currByte))
                    {
                        state = State.METHOD;
                        method.append(currByte);
                    }
                    break;
                case METHOD:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                        state = State.BEFORE_URI;
                    }
                    else
                    {
                        method.append(currByte);
                    }
                    break;
                case BEFORE_URI:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                    }
                    else
                    {
                        state = State.URI;
                        uri.append(currByte);
                    }
                    break;
                case URI:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                        state = State.BEFORE_VERSION;
                    }
                    else
                    {
                        uri.append(currByte);
                    }
                    break;
                case BEFORE_VERSION:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                    }
                    else
                    {
                        state = State.VERSION;
                        version.append(currByte);
                    }
                    break;
                case VERSION:
                    if (isLineSeparator(currByte))
                    {
                        state = State.AFTER_VERSION;
                        firstEOL = currByte;
                        onRequestLine(utf8Decode(method.getByteBuffer()), utf8Decode(uri.getByteBuffer()), utf8Decode(version.getByteBuffer()));
                    }
                    else
                    {
                        version.append(currByte);
                    }
                    break;
                case AFTER_VERSION:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                        {
                            if (currByte != firstEOL)
                                // We saw 2 different EOL chars, we are done
                                return done(true);
                        }
                        else
                        {
                            throw new ParseException();
                        }
                    }
                    // Push back the char to be processed by other scanners
                    buffer.position(buffer.position() - 1);
                    return done(true);
                default:
                    throw new ParseException();

            }
        }
        return done(false);
    }

    protected abstract void onRequestLine(String method, String uri, String version);

    private boolean done(boolean result)
    {
        if (result)
            reset();
        return result;
    }

    private void reset()
    {
        method.reset();
        uri.reset();
        version.reset();
        state = State.BEFORE_METHOD;
        firstEOL = 0;
    }

    private enum State
    {
        BEFORE_METHOD, METHOD, BEFORE_URI, URI, BEFORE_VERSION, VERSION, AFTER_VERSION
    }
}
