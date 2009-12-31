package org.cometd.bwtp.server.parser.push;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cometd.bwtp.parser.ParseException;
import org.cometd.bwtp.parser.push.BWTPPushParser;
import org.cometd.bwtp.parser.push.BWTPPushScanner;
import org.cometd.bwtp.parser.push.HeadersBWTPPushScanner;

/**
 * @version $Revision$ $Date$
 */
public abstract class ServerBWTPPushParser extends BWTPPushParser
{
    private State state = State.REQUEST_LINE;
    private boolean upgrade = true;
    private String method;
    private String uri;
    private String version;
    private Map<String, String> headers;
    private final BWTPPushScanner requestLineScanner = new RequestLineBWTPPushScanner()
    {
        @Override
        protected void onRequestLine(String method, String uri, String version)
        {
            ServerBWTPPushParser.this.method = method;
            ServerBWTPPushParser.this.uri = uri;
            ServerBWTPPushParser.this.version = version;
            ServerBWTPPushParser.this.headers = new LinkedHashMap<String, String>();
        }
    };
    private final BWTPPushScanner headersScanner = new HeadersBWTPPushScanner()
    {
        @Override
        protected void onHeader(String name, String value)
        {
            ServerBWTPPushParser.this.headers.put(name, value);
        }
    };

    @Override
    public void parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            if (upgrade)
                parseUpgradeRequest(buffer);
            else
                super.parse(buffer);
        }
    }

    protected void parseUpgradeRequest(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case REQUEST_LINE:
                    if (requestLineScanner.scan(buffer, -1))
                    {
                        state = State.REQUEST_HEADERS;
                    }
                    break;
                case REQUEST_HEADERS:
                    if (headersScanner.scan(buffer, -1))
                    {
                        upgrade = false;
                        upgradeRequest(method, uri, version, Collections.unmodifiableMap(headers));
                        return;
                    }
                    break;
                default:
                    throw new ParseException();
            }
        }
    }

    protected abstract void upgradeRequest(String method, String uri, String version, Map<String, String> headers);

    private enum State
    {
        REQUEST_LINE, REQUEST_HEADERS
    }
}
