package org.cometd.websocket.generator;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import com.webtide.wharf.io.async.AsyncCoordinator;
import org.cometd.websocket.Message;
import org.cometd.websocket.WebSocketException;

/**
 * @version $Revision$ $Date$
 */
public class StandardWebSocketGenerator implements WebSocketGenerator
{
    private final AsyncCoordinator coordinator;
    private final ByteBuffer buffer;

    public StandardWebSocketGenerator(AsyncCoordinator coordinator, ByteBuffer buffer)
    {
        this.coordinator = coordinator;
        this.buffer = buffer;
    }

    public void handshakeRequest(URI uri, String protocol) throws ClosedChannelException
    {
        String scheme = uri.getScheme();
        if (!"ws".equals(scheme) && !"wss".equals(scheme))
            throw new WebSocketException("WebSocket URI must have 'ws' or 'wss' scheme");

        StringBuilder handshake = new StringBuilder();
        handshake.append("GET ").append(uri.getPath()).append(" ").append("HTTP/1.1\r\n");
        handshake.append("Upgrade: WebSocket\r\n");
        handshake.append("Connection: Upgrade\r\n");

        StringBuilder host = new StringBuilder(uri.getHost());
        if (("ws".equals(scheme) && uri.getPort() != 80) ||
            ("wss".equals(scheme) && uri.getPort() != 443))
            host.append(":").append(uri.getPort());
        handshake.append("Host: ").append(host).append("\r\n");

        if (protocol != null && protocol.trim().length() > 0)
            handshake.append("WebSocket-Protocol: ").append(protocol).append("\r\n");

        handshake.append("\r\n");

        write(utf8Encode(handshake.toString()));
    }

    private void write(byte[] bytes) throws ClosedChannelException
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

    public void send(Message message) throws ClosedChannelException
    {
        write(message.encode());
    }

    private byte[] utf8Encode(String value)
    {
        try
        {
            return value.getBytes("UTF-8");
        }
        catch (UnsupportedEncodingException x)
        {
            throw new AssertionError(x);
        }
    }
}
