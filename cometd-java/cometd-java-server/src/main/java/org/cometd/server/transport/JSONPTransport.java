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
package org.cometd.server.transport;

import java.io.IOException;
import java.text.ParseException;
import java.util.regex.Pattern;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;

public class JSONPTransport extends AbstractStreamHttpTransport {
    public final static String PREFIX = "long-polling.jsonp";
    public final static String NAME = "callback-polling";
    public final static String CALLBACK_PARAMETER_OPTION = "callbackParameter";
    public final static String CALLBACK_PARAMETER_MAX_LENGTH_OPTION = "callbackParameterMaxLength";

    private final static Pattern CALLBACK_PATTERN = Pattern.compile("^[a-zA-Z0-9._\\-]+$");
    private final static byte[] MESSAGE_BEGIN = new byte[]{'(', '['};
    private final static byte[] MESSAGE_END = new byte[]{']', ')'};

    private String _callbackParam = "jsonp";
    private int _callbackMaxLength = 64;

    public JSONPTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init() {
        super.init();
        _callbackParam = getOption(CALLBACK_PARAMETER_OPTION, _callbackParam);
        _callbackMaxLength = getOption(CALLBACK_PARAMETER_MAX_LENGTH_OPTION, _callbackMaxLength);
        // This transport must deliver only via /meta/connect
        setMetaConnectDeliveryOnly(true);
    }

    @Override
    public boolean accept(HttpServletRequest request) {
        String callbackValue = request.getParameter(getCallbackParameter());
        return "GET".equals(request.getMethod()) && isCallbackValueValid(callbackValue);
    }

    @Override
    protected ServerMessage.Mutable[] parseMessages(HttpServletRequest request) throws IOException, ParseException {
        return parseMessages(request.getParameterValues(MESSAGE_PARAM));
    }

    public String getCallbackParameter() {
        return _callbackParam;
    }

    @Override
    protected ServletOutputStream beginWrite(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/javascript;charset=UTF-8");
        String callback = request.getParameter(_callbackParam);
        ServletOutputStream output = response.getOutputStream();
        output.write(callback.getBytes(response.getCharacterEncoding()));
        output.write(MESSAGE_BEGIN);
        return output;
    }

    @Override
    protected void endWrite(HttpServletResponse response, ServletOutputStream output) throws IOException {
        output.write(MESSAGE_END);
        output.close();
    }

    private boolean isCallbackValueValid(String callbackValue) {
        return callbackValue != null &&
                callbackValue.length() <= _callbackMaxLength &&
                CALLBACK_PATTERN.matcher(callbackValue).matches();
    }
}
