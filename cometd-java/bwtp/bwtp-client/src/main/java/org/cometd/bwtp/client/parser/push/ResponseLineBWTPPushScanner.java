package org.cometd.bwtp.client.parser.push;

import java.nio.ByteBuffer;

import org.cometd.bwtp.parser.ParseException;
import org.cometd.bwtp.parser.push.BWTPPushScanner;
import org.cometd.bwtp.parser.push.Token;

/**
 * @version $Revision: 849 $ $Date$
 */
public abstract class ResponseLineBWTPPushScanner extends BWTPPushScanner
{
    private State state = State.BEFORE_VERSION;
    private final Token version = new Token();
    private final Token codeToken = new Token();
    private int code;
    private final Token message = new Token();
    private byte firstEOL;

    @Override
    public boolean scan(ByteBuffer buffer, int limit)
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
                            throw new ParseException();
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
                            throw new ParseException();
                    }
                    else
                    {
                        state = State.CODE;
                        codeToken.append(currByte);
                    }
                    break;
                case CODE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();

                        try
                        {
                            code = Integer.parseInt(utf8Decode(codeToken.getByteBuffer()));
                        }
                        catch (NumberFormatException x)
                        {
                            throw new ParseException();
                        }

                        state = State.BEFORE_MESSAGE;
                    }
                    else
                    {
                        codeToken.append(currByte);
                    }
                    break;
                case BEFORE_MESSAGE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                    }
                    else
                    {
                        state = State.MESSAGE;
                        message.append(currByte);
                    }
                    break;
                case MESSAGE:
                    if (isLineSeparator(currByte))
                    {
                        state = State.AFTER_MESSAGE;
                        firstEOL = currByte;
                        onResponseLine(utf8Decode(version.getByteBuffer()), code, utf8Decode(message.getByteBuffer()));
                    }
                    else
                    {
                        message.append(currByte);
                    }
                    break;
                case AFTER_MESSAGE:
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

    private boolean done(boolean result)
    {
        if (result)
            reset();
        return result;
    }

    private void reset()
    {
        version.reset();
        codeToken.reset();
        code = 0;
        message.reset();
        state = State.BEFORE_VERSION;
        firstEOL = 0;
    }

    private enum State
    {
        BEFORE_VERSION, VERSION, BEFORE_CODE, CODE, BEFORE_MESSAGE, MESSAGE, AFTER_MESSAGE
    }

    protected abstract void onResponseLine(String version, int code, String message);
}
