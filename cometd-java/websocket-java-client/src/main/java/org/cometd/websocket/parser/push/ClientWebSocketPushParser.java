package org.cometd.websocket.parser.push;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cometd.websocket.WebSocketException;
import org.cometd.websocket.parser.WebSocketParserListener;

/**
 * @version $Revision$ $Date$
 */
public class ClientWebSocketPushParser extends WebSocketPushParser
{
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final WebSocketPushScanner responseLineScanner = new ResponseLineWebSocketPushScanner()
    {
        @Override
        protected void onResponseLine(ByteBuffer version, ByteBuffer code, ByteBuffer message)
        {
            ClientWebSocketPushParser.this.code = Integer.parseInt(UTF8.decode(code).toString());
            headers = new LinkedHashMap<String, String>();
        }
    };
    private final WebSocketPushScanner responseHeaderScanner = new HeaderWebSocketPushScanner()
    {
        @Override
        protected void onHeader(ByteBuffer name, ByteBuffer value)
        {
            String headerName = UTF8.decode(name).toString();
            String headerValue = UTF8.decode(value).toString();
            ClientWebSocketPushParser.this.headers.put(headerName, headerValue);
        }
    };
    private State state = State.RESPONSE_LINE;
    private int code = 0;
    private Map<String, String> headers;
    private boolean handshake = true;

    // TODO: really needed ?
    @Override
    protected void reset()
    {
        super.reset();
        code = 0;
        headers = null;
        handshake = true;
        state = State.RESPONSE_LINE;
    }

    @Override
    public void parse(ByteBuffer buffer)
    {
        if (handshake)
            parseHandshakeResponse(buffer);
        else
            super.parse(buffer);
    }

    private void parseHandshakeResponse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case RESPONSE_LINE:
                    if (responseLineScanner.scan(buffer))
                        state = State.RESPONSE_HEADERS;
                    break;
                case RESPONSE_HEADERS:
                    if (responseHeaderScanner.scan(buffer))
                    {
                        notifyOnHandshakeResponse();
                        handshake = false;
                        return;
                    }
                    break;
                default:
                    throw new WebSocketException();
            }
        }
    }

    private void notifyOnHandshakeResponse()
    {
        for (WebSocketParserListener listener : getListeners())
        {
            try
            {
                listener.onHandshakeResponse(code, Collections.unmodifiableMap(headers));
            }
            catch (Throwable x)
            {
                logger.debug("Exception while calling listener " + listener, x);
            }
        }
    }

    private enum State
    {
        RESPONSE_LINE, RESPONSE_HEADERS
    }
}
