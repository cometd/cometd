/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.server.http;

import java.io.IOException;
import java.text.ParseException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;

public class JSONTransport extends AbstractStreamHttpTransport {
    public final static String PREFIX = "long-polling.json";
    public final static String NAME = "long-polling";

    private boolean _jsonDebug = false;

    public JSONTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init() {
        super.init();
        _jsonDebug = getOption(JSON_DEBUG_OPTION, _jsonDebug);
    }

    @Override
    public boolean accept(HttpServletRequest request) {
        return "POST".equals(request.getMethod());
    }

    @Override
    protected ServerMessage.Mutable[] parseMessages(HttpServletRequest request) throws IOException, ParseException {
        String charset = request.getCharacterEncoding();
        if (charset == null) {
            request.setCharacterEncoding("UTF-8");
        }
        String contentType = request.getContentType();
        if (contentType == null || contentType.startsWith("application/json")) {
            return parseMessages(request.getReader(), _jsonDebug);
        } else if (contentType.startsWith("application/x-www-form-urlencoded")) {
            return parseMessages(request.getParameterValues(MESSAGE_PARAM));
        } else {
            throw new IOException("Invalid Content-Type " + contentType);
        }
    }

    @Override
    protected ServletOutputStream beginWrite(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        ServletOutputStream output = response.getOutputStream();
        output.write('[');
        return output;
    }

    @Override
    protected void endWrite(HttpServletResponse response, ServletOutputStream output) throws IOException {
        output.write(']');
        output.close();
    }
}
