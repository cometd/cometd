package org.cometd.bwtp.parser.push;

import java.nio.ByteBuffer;

import org.cometd.bwtp.BWTPActionType;
import org.cometd.bwtp.BWTPFrameType;
import org.cometd.bwtp.parser.ParseException;

/**
 * @version $Revision$ $Date$
 */
public abstract class FrameLineBWTPPushScanner extends BWTPPushScanner
{
    private final Token typeToken = new Token();
    private BWTPFrameType type = null;
    private final Token channelToken = new Token();
    private String channel;
    private final Token frameSizeToken = new Token();
    private int frameSize;
    private final Token messageSizeToken = new Token();
    private final Token actionToken = new Token();
    private BWTPActionType action;
    private final Token argumentsToken = new Token();
    private State state = State.BEFORE_FRAME_TYPE;

    @Override
    public boolean scan(ByteBuffer buffer, int limit)
    {
        while (buffer.hasRemaining())
        {
            byte currByte = buffer.get();
            switch (state)
            {
                case BEFORE_FRAME_TYPE:
                    // Eat whitespace
                    if (!isWhitespace(currByte))
                    {
                        state = State.FRAME_TYPE;
                        typeToken.append(currByte);
                    }
                    break;
                case FRAME_TYPE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();

                        type = BWTPFrameType.from(utf8Decode(typeToken.getByteBuffer()));
                        if (type == null)
                            throw new ParseException();

                        state = State.BEFORE_CHANNEL;
                    }
                    else
                    {
                        typeToken.append(currByte);
                    }
                    break;
                case BEFORE_CHANNEL:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                    }
                    else
                    {
                        state = State.CHANNEL;
                        channelToken.append(currByte);
                    }
                    break;
                case CHANNEL:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();

                        channel = utf8Decode(channelToken.getByteBuffer());
                        state = State.BEFORE_FRAME_SIZE;
                    }
                    else
                    {
                        if (!isNumberOrStar(currByte))
                            throw new ParseException();

                        channelToken.append(currByte);
                    }
                    break;
                case BEFORE_FRAME_SIZE:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                    }
                    else
                    {
                        if (!isNumber(currByte))
                            throw new ParseException();

                        state = State.FRAME_SIZE;
                        frameSizeToken.append(currByte);
                    }
                    break;
                case FRAME_SIZE:
                    if (isWhitespace(currByte))
                    {
                        if (type == BWTPFrameType.HEADER)
                        {
                            if (isLineSeparator(currByte))
                                throw new ParseException();

                            frameSize = Integer.parseInt(utf8Decode(frameSizeToken.getByteBuffer()));
                            state = State.BEFORE_ACTION;
                        }
                        else
                        {
                            if (isLineSeparator(currByte))
                            {
                                frameSize = Integer.parseInt(utf8Decode(frameSizeToken.getByteBuffer()));
                                state = State.EOL;
                                notifyMessageFrameLine(channel, frameSize, -1);
                            }
                            else
                            {
                                throw new ParseException();
                            }
                        }
                    }
                    else
                    {
                        if (currByte == '/')
                        {
                            if (type == BWTPFrameType.HEADER)
                                throw new ParseException();

                            state = State.MESSAGE_SIZE;
                        }
                        else
                        {
                            if (!isNumber(currByte))
                                throw new ParseException();

                            frameSizeToken.append(currByte);
                        }
                    }
                    break;
                case MESSAGE_SIZE:
                    if (isLineSeparator(currByte))
                    {
                        int messageSize;
                        String messageSizeString = utf8Decode(messageSizeToken.getByteBuffer());
                        if ("*".equals(messageSizeString))
                        {
                            messageSize = 0;
                        }
                        else
                        {
                            try
                            {
                                messageSize = Integer.parseInt(messageSizeString);
                            }
                            catch (NumberFormatException x)
                            {
                                throw new ParseException();
                            }
                        }
                        state = State.EOL;
                        notifyMessageFrameLine(channel, frameSize, messageSize);
                    }
                    else
                    {
                        if (!isNumberOrStar(currByte))
                            throw new ParseException();

                        messageSizeToken.append(currByte);
                    }
                    break;
                case BEFORE_ACTION:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                    }
                    else
                    {
                        state = State.ACTION;
                        actionToken.append(currByte);
                    }
                    break;
                case ACTION:
                    if (isWhitespace(currByte))
                    {
                        try
                        {
                            action = BWTPActionType.valueOf(utf8Decode(actionToken.getByteBuffer()));
                        }
                        catch (IllegalArgumentException x)
                        {
                            throw new ParseException();
                        }

                        if (action == BWTPActionType.OPEN)
                        {
                            if (isLineSeparator(currByte))
                                throw new ParseException();

                            state = State.BEFORE_ARGUMENTS;
                        }
                        else
                        {
                            if (isLineSeparator(currByte))
                            {
                                state = State.EOL;
                                notifyHeaderFrameLine(channel, frameSize, action, null);
                            }
                            else
                            {
                                throw new ParseException();
                            }
                        }
                    }
                    else
                    {
                        actionToken.append(currByte);
                    }
                    break;
                case BEFORE_ARGUMENTS:
                    if (isWhitespace(currByte))
                    {
                        if (isLineSeparator(currByte))
                            throw new ParseException();
                    }
                    else
                    {
                        state = State.ARGUMENTS;
                        argumentsToken.append(currByte);
                    }
                    break;
                case ARGUMENTS:
                    if (isLineSeparator(currByte))
                    {
                        String arguments = utf8Decode(argumentsToken.getByteBuffer());
                        state = State.EOL;
                        notifyHeaderFrameLine(channel, frameSize, action, arguments);
                    }
                    else
                    {
                        argumentsToken.append(currByte);
                    }
                    break;
                case EOL:
                    if (isLineSeparator(currByte))
                    {
                        return done(true);
                    }
                    else
                    {
                        // Push back the character and return
                        buffer.position(buffer.position() - 1);
                        return done(true);
                    }
                default:
                    throw new ParseException();
            }
        }
        return done(false);
    }

    private boolean isNumberOrStar(byte currByte)
    {
        return currByte == '*' || isNumber(currByte);
    }

    private boolean isNumber(byte currByte)
    {
        return currByte >= '0' && currByte <= '9';
    }

    private boolean done(boolean result)
    {
        if (result)
            reset();
        return result;
    }

    private void reset()
    {
        typeToken.reset();
        type = null;
        channelToken.reset();
        frameSizeToken.reset();
        messageSizeToken.reset();
        actionToken.reset();
        action = null;
        argumentsToken.reset();
        state = State.BEFORE_FRAME_TYPE;
    }

    protected abstract void notifyHeaderFrameLine(String channel, int frameSize, BWTPActionType action, String arguments);

    protected abstract void notifyMessageFrameLine(String channel, int frameSize, int messageSize);

    private enum State
    {
        BEFORE_FRAME_TYPE, FRAME_TYPE, BEFORE_CHANNEL, CHANNEL, BEFORE_FRAME_SIZE, FRAME_SIZE, MESSAGE_SIZE,
        BEFORE_ACTION, ACTION, BEFORE_ARGUMENTS, ARGUMENTS, EOL
    }
}
