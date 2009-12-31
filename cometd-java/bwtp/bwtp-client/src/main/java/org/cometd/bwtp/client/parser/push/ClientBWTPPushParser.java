package org.cometd.bwtp.client.parser.push;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cometd.bwtp.parser.BWTPParser;
import org.cometd.bwtp.parser.ParseException;
import org.cometd.bwtp.parser.push.BWTPPushParser;
import org.cometd.bwtp.parser.push.BWTPPushScanner;
import org.cometd.bwtp.parser.push.HeadersBWTPPushScanner;

/**
 * @version $Revision$ $Date$
 */
public abstract class ClientBWTPPushParser extends BWTPPushParser implements BWTPParser
{
    private boolean upgrade = true;
    private State state = State.RESPONSE_LINE;
    private String version;
    private int code;
    private String message;
    private Map<String, String> headers;
    private final BWTPPushScanner responseLineScanner = new ResponseLineBWTPPushScanner()
    {
        @Override
        protected void onResponseLine(String version, int code, String message)
        {
            ClientBWTPPushParser.this.version = version;
            ClientBWTPPushParser.this.code = code;
            ClientBWTPPushParser.this.message = message;
            ClientBWTPPushParser.this.headers = new LinkedHashMap<String, String>();
        }
    };
    private final BWTPPushScanner responseHeadersScanner = new HeadersBWTPPushScanner()
    {
        @Override
        protected void onHeader(String name, String value)
        {
            ClientBWTPPushParser.this.headers.put(name, value);
        }
    };

    @Override
    public void parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            if (upgrade)
                parseUpgradeResponse(buffer);
            else
                super.parse(buffer);
        }
    }

    protected void parseUpgradeResponse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case RESPONSE_LINE:
                    if (responseLineScanner.scan(buffer, -1))
                    {
                        state = State.RESPONSE_HEADERS;
                    }
                    break;
                case RESPONSE_HEADERS:
                    if (responseHeadersScanner.scan(buffer, -1))
                    {
                        upgrade = false;
                        upgradeResponse(version, code, message, Collections.unmodifiableMap(headers));
                        return;
                    }
                    break;
                default:
                    throw new ParseException();
            }
        }
    }

    protected abstract void upgradeResponse(String version, int code, String message, Map<String, String> headers);

    private enum State
    {
        RESPONSE_LINE, RESPONSE_HEADERS
    }
}
