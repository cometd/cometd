package org.cometd.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;


public class JSONPTransport extends LongPollingTransport
{
    public final static String PREFIX="long-polling.jsonp";
    public final static String NAME="callback-polling";
    public final static String MIME_TYPE_OPTION="mimeType";
    public final static String CALLBACK_PARAMETER_OPTION="callbackParameter";

    private String _mimeType="text/javascript;charset=UTF-8";
    private String _callbackParam="jsonp";

    public JSONPTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux,NAME);
        setOptionPrefix(PREFIX);
    }


    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.server.transport.LongPollingTransport#isAlwaysFlushingAfterHandle()
     */
    @Override
    protected boolean isAlwaysFlushingAfterHandle()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.server.transport.JSONTransport#init()
     */
    @Override
    protected void init()
    {
        super.init();
        _callbackParam=getOption(CALLBACK_PARAMETER_OPTION,_callbackParam);
        _mimeType=getOption(MIME_TYPE_OPTION,_mimeType);
        // This transport must deliver only via /meta/connect
        setMetaConnectDeliveryOnly(true);
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "GET".equals(request.getMethod()) && request.getParameter(getCallbackParameter())!=null;
    }

    /* ------------------------------------------------------------ */
    public String getCallbackParameter()
    {
        return _callbackParam;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected PrintWriter send(HttpServletRequest request,HttpServletResponse response,PrintWriter writer, ServerMessage message) throws IOException
    {
        if (writer==null)
        {
            response.setContentType(_mimeType);

            String callback=request.getParameter(_callbackParam);
            writer = response.getWriter();
            writer.append(callback);
            writer.append("([");
        }
        else
            writer.append(',');
        writer.append(message.getJSON());
        return writer;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void complete(PrintWriter writer) throws IOException
    {
        writer.append("])\r\n");
        writer.close();
    }


}
