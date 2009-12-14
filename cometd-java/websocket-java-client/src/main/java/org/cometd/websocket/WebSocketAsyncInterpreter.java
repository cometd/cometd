package org.cometd.websocket;

import java.nio.ByteBuffer;
import java.util.Map;

import org.cometd.websocket.parser.WebSocketParser;
import org.cometd.websocket.parser.WebSocketParserListener;
import org.cometd.websocket.client.Listener;
import com.webtide.wharf.io.async.AsyncCoordinator;
import com.webtide.wharf.io.async.AsyncInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketAsyncInterpreter implements AsyncInterpreter, WebSocketParserListener
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final AsyncCoordinator coordinator;
    private final WebSocketParser parser;
    private final Listener listener;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private volatile State state;
    private volatile boolean complete;

    public WebSocketAsyncInterpreter(AsyncCoordinator coordinator, WebSocketParser parser, Listener listener)
    {
        this.coordinator = coordinator;
        this.parser = parser;
        this.parser.registerListener(this);
        this.listener = listener;
        this.readBuffer = ByteBuffer.allocate(1024);
        this.writeBuffer = ByteBuffer.allocate(1024);
        this.state = State.HANDSHAKE;
        this.complete = false;
    }

    public ByteBuffer getReadBuffer()
    {
        return readBuffer;
    }

    public ByteBuffer getWriteBuffer()
    {
        return writeBuffer;
    }

    public void readFrom(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            logger.debug("Interpreting {} bytes", buffer.remaining());

            complete = false;
            parser.parse(buffer);

            switch (state)
            {
                case HANDSHAKE:
                    if (complete)
                        state = State.FRAMES;
                    else
                        coordinator.needsRead(true);
                    break;
                case FRAMES:
                    if (!complete)
                        coordinator.needsRead(true);
                    break;
                default:
                    throw new WebSocketException();
            }
        }
    }

    public void onHandshakeRequest(String path, Map<String, String> headers)
    {
        complete = true;
    }

    public void onHandshakeResponse(int code, Map<String, String> headers)
    {
        complete = true;
        if (code == 101)
        {
            listener.onOpen(headers);
        }
        else
        {
            listener.onClose();
        }
    }

    public void onMessage(Message message)
    {
        complete = true;
        listener.onMessage(message);
    }

    private enum State
    {
        HANDSHAKE, FRAMES
    }
}
