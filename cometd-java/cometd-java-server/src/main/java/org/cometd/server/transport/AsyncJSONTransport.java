package org.cometd.server.transport;

import javax.servlet.http.HttpServletRequest;

import org.cometd.server.BayeuxServerImpl;

public class AsyncJSONTransport extends AsyncLongPollingTransport
{
    private final static String PREFIX = "long-polling.json";
    private final static String NAME = "long-polling";

    public AsyncJSONTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    protected void init()
    {
        super.init();
    }

    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "POST".equalsIgnoreCase(request.getMethod());
    }
}
