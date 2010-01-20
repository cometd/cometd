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

import org.cometd.Bayeux;
import org.cometd.Message;
import org.eclipse.jetty.util.ajax.JSON;


public class JSONTransport extends AbstractTransport
{
    private int _responses;
    private PrintWriter _out;
    private String _start;
    private String _end;

    /* ------------------------------------------------------------ */
    public JSONTransport()
    {
        _start="[";
        _end="]\r\n";
    }

    /* ------------------------------------------------------------ */
    public synchronized void send(Message message) throws IOException
    {
        if (message!=null)
        {
            if (message.size()==0)
                throw new IllegalStateException();

            String r=(message instanceof MessageImpl)
                ?((MessageImpl)message).getJSON()
                :JSON.toString(message);

            HttpServletResponse response=getResponse();

            switch(_responses)
            {
                case 0:
                    response.setStatus(200);
                    response.setContentType(Bayeux.JSON_CONTENT_TYPE);
                    _out=response.getWriter();
                    _out.write(_start);
                    _out.write(r);
                    break;

                default:
                    _out.write(',');
                    _out.write(r);
            }

            _responses++;
        }
    }

    /* ------------------------------------------------------------ */
    public synchronized void complete() throws IOException
    {
        if (_responses==0)
        {
            HttpServletResponse response=getResponse();
            response.setStatus(200);
            _out=response.getWriter();
            _out.write(_start);
        }
        _out.write(_end);
        _out.close();
        _responses=0;
    }

    /* ------------------------------------------------------------ */
    public boolean isMetaConnectDeliveryOnly()
    {
        return false;
    }
}
