package org.cometd.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;

public class JSONTransport extends LongPollingTransport
{
    public final static String PREFIX = "long-polling.json";
    public final static String NAME = "long-polling";
    public final static String MIME_TYPE_OPTION = "mimeType";

    private String _mimeType = "application/json;charset=UTF-8";

    public JSONTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    protected boolean isAlwaysFlushingAfterHandle()
    {
        return false;
    }

    @Override
    protected void init()
    {
        super.init();
        _mimeType = getOption(MIME_TYPE_OPTION, _mimeType);
    }

    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "POST".equals(request.getMethod());
    }

    @Override
    protected PrintWriter send(HttpServletRequest request, HttpServletResponse response, PrintWriter writer, ServerMessage message) throws IOException
    {
        if (writer == null)
        {
            response.setContentType(_mimeType);
            writer = response.getWriter();
            writer.append('[');
        }
        else
        {
            writer.append(',');
        }
        writer.append(message.getJSON());
        return writer;
    }

    @Override
    protected void complete(PrintWriter writer) throws IOException
    {
        writer.append("]\n");
        writer.close();
    }
}
