// ========================================================================
// Copyright 2007 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.server;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletResponse;

import org.cometd.Message;
import org.eclipse.jetty.util.ajax.JSON;

/* ------------------------------------------------------------ */
/**
 * @author aabeling
 * @author gregw
 * 
 */
public class JSONPTransport extends AbstractTransport
{
    public final static String __DEFAULT_CALLBACK="jsonpcallback";
    int _responses=0;
    PrintWriter _out;
    String _jsonp=null;
    String _mimeType;

    public JSONPTransport(String jsonp)
    {
        _mimeType="text/javascript; charset=utf-8";
        _jsonp=jsonp;
    }

    public void send(Message message) throws IOException
    {
        if (message != null)
        {
            if (_responses == 0)
            {
                HttpServletResponse response=getResponse();
                response.setContentType(_mimeType);
                _out=response.getWriter();
                _out.write(this._jsonp == null?__DEFAULT_CALLBACK:_jsonp);
                _out.write("([");
            }
            else
            {
                _out.write(",\r\n");
            }

            String r=(message instanceof MessageImpl)?((MessageImpl)message).getJSON():JSON.toString(message);
            _responses++;
            _out.write(r);
        }
    }

    public void complete() throws IOException
    {
        HttpServletResponse response=getResponse();
        response.setStatus(200);

        if (_responses == 0)
        {
            response.setContentType(_mimeType);
            _out=response.getWriter();
            _out.write(this._jsonp == null?__DEFAULT_CALLBACK:_jsonp);
            _out.write("([");
        }
        _out.write("])\r\n");
        _out.close();
    }

    /* ------------------------------------------------------------ */
    public boolean isMetaConnectDeliveryOnly()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public String getJsonp()
    {
        return _jsonp;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "JSONPTransport[jsonp=" + this._jsonp + "]";
    }
}
