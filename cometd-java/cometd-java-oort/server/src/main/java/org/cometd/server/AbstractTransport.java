package org.cometd.server;

//========================================================================
//Copyright 2007 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

import java.io.IOException;
import javax.servlet.http.HttpServletResponse;

import org.cometd.Message;

public abstract class AbstractTransport implements Transport
{
    private HttpServletResponse _response;
    private Message _pollReply;

    public void setResponse(HttpServletResponse response) throws IOException
    {
        _response=response;
    }

    public HttpServletResponse getResponse()
    {
        return _response;
    }

    public Message getMetaConnectReply()
    {
        return _pollReply;
    }

    public void setMetaConnectReply(Message reply)
    {
        _pollReply=reply;
    }
}
