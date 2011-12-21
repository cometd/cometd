/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.ParseException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;

public class JSONTransport extends LongPollingTransport
{
    public final static String PREFIX = "long-polling.json";
    public final static String NAME = "long-polling";
    public final static String MIME_TYPE_OPTION = "mimeType";

    private boolean _jsonDebug = false;
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
        _jsonDebug = getOption(JSON_DEBUG_OPTION, _jsonDebug);
        _mimeType = getOption(MIME_TYPE_OPTION, _mimeType);
    }

    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "POST".equals(request.getMethod());
    }

    @Override
    protected ServerMessage.Mutable[] parseMessages(HttpServletRequest request) throws IOException, ParseException
    {
        String charset = request.getCharacterEncoding();
        if (charset == null)
            request.setCharacterEncoding("UTF-8");
        String contentType = request.getContentType();
        if (contentType == null || contentType.startsWith("application/json"))
            return parseMessages(request.getReader(), _jsonDebug);
        else if (contentType.startsWith("application/x-www-form-urlencoded"))
            return parseMessages(request.getParameterValues(MESSAGE_PARAM));
        else
            throw new IOException("Invalid Content-Type " + contentType);
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
        writer.append("]");
        writer.close();
    }
}
