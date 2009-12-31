package org.cometd.bwtp.parser.push;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cometd.bwtp.BWTPActionType;
import org.cometd.bwtp.BWTPFrameType;
import org.cometd.bwtp.BWTPHeaderFrame;
import org.cometd.bwtp.BWTPMessageFrame;
import org.cometd.bwtp.parser.BWTPParser;
import org.cometd.bwtp.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class BWTPPushParser implements BWTPParser
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private BWTPFrameType frameType;
    private String channel;
    private int frameSize;
    private int currentFrameSize;
    private int messageSize;
    private BWTPActionType action;
    private String arguments;
    private Map<String, String> headers;
    private byte[] content;
    private State state = State.FRAME_LINE;
    private final BWTPPushScanner frameLineScanner = new FrameLineBWTPPushScanner()
    {
        @Override
        protected void notifyHeaderFrameLine(String channel, int frameSize, BWTPActionType action, String arguments)
        {
            BWTPPushParser.this.frameType = BWTPFrameType.HEADER;
            BWTPPushParser.this.channel = channel;
            BWTPPushParser.this.frameSize = frameSize;
            BWTPPushParser.this.action = action;
            BWTPPushParser.this.arguments = arguments;
            BWTPPushParser.this.headers = new LinkedHashMap<String, String>();
        }

        @Override
        protected void notifyMessageFrameLine(String channel, int frameSize, int messageSize)
        {
            BWTPPushParser.this.frameType = BWTPFrameType.MESSAGE;
            BWTPPushParser.this.channel = channel;
            BWTPPushParser.this.frameSize = frameSize;
            BWTPPushParser.this.currentFrameSize = frameSize;
            BWTPPushParser.this.messageSize = messageSize;
            BWTPPushParser.this.content = new byte[frameSize];
        }
    };
    private final BWTPPushScanner headersScanner = new HeadersBWTPPushScanner()
    {
        @Override
        protected void onHeader(String name, String value)
        {
            BWTPPushParser.this.headers.put(name, value);
        }
    };

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    public void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    public void parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_LINE:
                    if (frameLineScanner.scan(buffer, -1))
                    {
                        switch (frameType)
                        {
                            case HEADER:
                                if (frameSize > 0)
                                {
                                    state = State.FRAME_HEADERS;
                                }
                                else
                                {
                                    notifyHeaderFrame(new BWTPHeaderFrame(channel, frameSize, action, arguments, headers));
                                    reset();
                                    return;
                                }
                                break;
                            case MESSAGE:
                                if (frameSize > 0)
                                {
                                    state = State.FRAME_BODY;
                                }
                                else
                                {
                                    notifyMessageFrame(new BWTPMessageFrame(channel, frameSize, messageSize, content));
                                    reset();
                                    return;
                                }
                                break;
                            default:
                                throw new ParseException();
                        }
                    }
                    break;
                case FRAME_HEADERS:
                    if (headersScanner.scan(buffer, frameSize))
                    {
                        notifyHeaderFrame(new BWTPHeaderFrame(channel, frameSize, action, arguments, headers));
                        reset();
                        return;
                    }
                    break;
                case FRAME_BODY:
                    if (currentFrameSize > 0)
                    {
                        content[frameSize - currentFrameSize] = buffer.get();
                        --currentFrameSize;
                        if (currentFrameSize == 0)
                        {
                            notifyMessageFrame(new BWTPMessageFrame(channel, frameSize, messageSize, content));
                            reset();
                            return;
                        }
                    }
                    else
                    {
                        notifyMessageFrame(new BWTPMessageFrame(channel, frameSize, messageSize, content));
                        reset();
                        return;
                    }
                    break;
                default:
                    throw new ParseException();
            }
        }
    }

    private void notifyHeaderFrame(BWTPHeaderFrame frame)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onHeaderFrame(frame);
            }
            catch (Exception x)
            {
                logger.debug("Exception while calling listener " + listener, x);
            }
        }
    }

    private void notifyMessageFrame(BWTPMessageFrame frame)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onMessageFrame(frame);
            }
            catch (Exception x)
            {
                logger.debug("Exception while calling listener " + listener, x);
            }
        }
    }

    private void reset()
    {
        frameType = null;
        channel = null;
        frameSize = 0;
        currentFrameSize = 0;
        messageSize = 0;
        action = null;
        arguments = null;
        headers = null;
        content = null;
        state = State.FRAME_LINE;
    }

    private enum State
    {
        FRAME_LINE, FRAME_HEADERS, FRAME_BODY
    }
}
