package org.cometd.bwtp.generator;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Map;

import org.cometd.bwtp.BWTPActionType;
import org.cometd.wharf.async.AsyncCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardBWTPGenerator implements BWTPGenerator
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final AsyncCoordinator coordinator;
    private final ByteBuffer buffer;
    private final String basePath;
    private int channel;

    public StandardBWTPGenerator(AsyncCoordinator coordinator, ByteBuffer buffer, String basePath)
    {
        this.coordinator = coordinator;
        this.buffer = buffer;
        this.basePath = basePath;
    }

    public String open(String path, Map<String, String> headers, boolean even) throws ClosedChannelException
    {
        String finalPath = path;
        if (!path.startsWith("/"))
        {
            finalPath = basePath;
            if (!finalPath.endsWith("/"))
                finalPath += "/";
            finalPath += path;
        }
        String channelId = newChannel(even);
        String frame = bwh(channelId, BWTPActionType.OPEN, finalPath, headers);
        logger.debug("Writing frame\r\n{}", frame);
        write(utf8Encode(frame));
        return channelId;
    }

    public void opened(String channelId, Map<String, String> headers) throws ClosedChannelException
    {
        String frame = bwh(channelId, BWTPActionType.OPENED, null, headers);
        logger.debug("Writing frame\r\n{}", frame);
        write(utf8Encode(frame));
    }

    public void header(String channelId, Map<String, String> headers) throws ClosedChannelException
    {
        String frame = bwh(channelId, BWTPActionType.MESSAGE, null, headers);
        logger.debug("Writing frame\r\n{}", frame);
        write(utf8Encode(frame));
    }

    public void message(String channelId, byte[] content) throws ClosedChannelException
    {
        StringBuilder builder = new StringBuilder();
        builder.append("BWM ");
        builder.append(channelId);
        builder.append(" ");
        builder.append(content.length);
        builder.append("\r\n");
        byte[] line = utf8Encode(builder.toString());
        byte[] frame = new byte[line.length + content.length];
        System.arraycopy(line, 0, frame, 0, line.length);
        System.arraycopy(content, 0, frame, line.length, content.length);
        logger.debug("Writing frame\r\n{}[{} bytes]\r\n", builder, content.length);
        write(frame);
    }

    public void close(String channelId, Map<String, String> headers) throws ClosedChannelException
    {
        String frame = bwh(channelId, BWTPActionType.CLOSE, null, headers);
        logger.debug("Writing frame\r\n{}", frame);
        write(utf8Encode(frame));
    }

    public void closed(String channelId, Map<String, String> headers) throws ClosedChannelException
    {
        String frame = bwh(channelId, BWTPActionType.CLOSED, null, headers);
        logger.debug("Writing frame\r\n{}", frame);
        write(utf8Encode(frame));
    }

    private String bwh(String channelId, BWTPActionType action, String arguments, Map<String, String> headers)
    {
        String body = bodyFrom(headers);
        int frameSize = utf8Encode(body).length;
        StringBuilder builder = new StringBuilder();
        builder.append("BWH ");
        builder.append(channelId);
        builder.append(" ");
        builder.append(frameSize);
        builder.append(" ");
        builder.append(action.name());
        if (arguments != null)
        {
            builder.append(" ");
            builder.append(arguments);
        }
        builder.append("\r\n");
        builder.append(body);
        return builder.toString();
    }

    private String bodyFrom(Map<String, String> headers)
    {
        StringBuilder builder = new StringBuilder();
        if (headers != null)
        {
            for (Map.Entry<String, String> entry : headers.entrySet())
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        return builder.toString();
    }

    private String newChannel(boolean even)
    {
        int result = channel;
        channel += 2; // BWTP Specification, 4.1.1
        if (!even)
            result += 1;
        return String.valueOf(result);
    }

    protected byte[] utf8Encode(String string)
    {
        try
        {
            return string.getBytes("UTF-8");
        }
        catch (UnsupportedEncodingException x)
        {
            throw new AssertionError(x);
        }
    }

    protected void write(byte[] bytes) throws ClosedChannelException
    {
        int offset = 0;
        int count = Math.min(bytes.length, buffer.capacity());
        while (offset < bytes.length)
        {
            buffer.clear();
            buffer.put(bytes, offset, count);
            offset += count;
            buffer.flip();
            coordinator.writeFrom(buffer);
        }
    }
}
