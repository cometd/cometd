package org.cometd.server.transports;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;


public class JSONPTransport extends LongPollingTransport
{
    public final static String NAME="callback-polling";
    public final static String MIME_TYPE_OPTION="mimeType";
    public final static String CALLBACK_PARAMETER_OPTION="callbackParameter";
    
    protected String _mimeType="text/javascript;charset=UTF-8";
    private String _callbackParam="jsonp";
    
    public JSONPTransport(BayeuxServerImpl bayeux,Map<String,Object> options)
    {
        super(bayeux,NAME,options);
        _prefix.add("jsonp");
        
        setOption(CALLBACK_PARAMETER_OPTION,_callbackParam);
        setOption(MIME_TYPE_OPTION,_mimeType);
        _metaConnectDeliveryOnly=true;
        setOption(META_CONNECT_DELIVERY_OPTION,_metaConnectDeliveryOnly);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.server.transports.JSONTransport#init()
     */
    @Override
    protected void init()
    {
        super.init();
        _callbackParam=getOption(CALLBACK_PARAMETER_OPTION,_callbackParam);
        _mimeType=getOption(MIME_TYPE_OPTION,_mimeType);
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
