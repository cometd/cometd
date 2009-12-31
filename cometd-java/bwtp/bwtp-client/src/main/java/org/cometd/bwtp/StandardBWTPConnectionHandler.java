package org.cometd.bwtp;

import java.util.Map;

import org.cometd.bwtp.parser.BWTPParser;

/**
 * @version $Revision$ $Date$
 */
public class StandardBWTPConnectionHandler implements BWTPParser.Listener
{
    private final BWTPListener listener;
    private final IBWTPConnection connection;

    public StandardBWTPConnectionHandler(BWTPListener listener, IBWTPConnection connection)
    {
        this.listener = listener;
        this.connection = connection;
    }

    public void onHeaderFrame(BWTPHeaderFrame frame)
    {
        switch (frame.getAction())
        {
            case OPEN:
                handleOpen(frame);
                break;
            case OPENED:
                handleOpened(frame);
                break;
            case MESSAGE:
                handleHeader(frame);
                break;
            case CLOSE:
                handleClose(frame);
                break;
            case CLOSED:
                handleClosed(frame);
                break;
            default:
                throw new IllegalStateException();
        }
    }

    public void onMessageFrame(BWTPMessageFrame frame)
    {
        BWTPChannel channel = connection.findChannel(frame.getChannelId());
        if (channel != null)
        {
            listener.onMessage(channel, frame);
        }
    }

    private void handleOpen(BWTPHeaderFrame frame)
    {
        // TODO: check that the channel has not been opened already
        Map<String, String> headers = listener.onOpen(frame);
        connection.opened(frame.getChannelId(), headers);
        // TODO: not guaranteed that the notification below broadcasts before others that arrive from server:
        // TODO: server receives opened and sends immediately a CLOSE which may be broadcasted before this OPEN
        BWTPChannel channel = connection.findChannel(frame.getChannelId());
        if (channel != null)
        {
            listener.onHeader(channel, frame);
        }
    }

    private void handleOpened(BWTPHeaderFrame frame)
    {
        BWTPChannel channel = connection.findChannel(frame.getChannelId());
        if (channel != null)
        {
            listener.onHeader(channel, frame);
        }
    }

    private void handleHeader(BWTPHeaderFrame frame)
    {
        BWTPChannel channel = connection.findChannel(frame.getChannelId());
        if (channel != null)
        {
            listener.onHeader(channel, frame);
        }
    }

    private void handleClose(BWTPHeaderFrame frame)
    {
        String channelId = frame.getChannelId();
        BWTPChannel channel = connection.findChannel(channelId);
        // Pass in a null channel to indicate we received the '*' channelId
        Map<String, String> headers = listener.onClose(channel, frame);
        connection.closed(channelId, headers);
    }

    private void handleClosed(BWTPHeaderFrame frame)
    {
        BWTPChannel channel = connection.findChannel(frame.getChannelId());
        if (channel != null)
        {
            listener.onHeader(channel, frame);
        }
    }
}
