/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.server.servlet.transport;

import java.io.IOException;
import java.text.ParseException;
import java.util.regex.Pattern;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.spi.CometDOutput;
import org.cometd.server.spi.CometDRequest;
import org.cometd.server.spi.CometDResponse;

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
    public boolean accept(CometDRequest request) {
        String[] callbackValue = request.getParameterValues(getCallbackParameter());
        return "GET".equals(request.getMethod()) && isCallbackValueValid(callbackValue);
    }

    @Override
    protected ServerMessage.Mutable[] parseMessages(CometDRequest request) throws IOException, ParseException {
        return parseMessages(request.getParameterValues(MESSAGE_PARAM));
    }

    public String getCallbackParameter() {
        return _callbackParam;
    }

    @Override
    protected CometDOutput beginWrite(CometDRequest request, CometDResponse response) throws IOException {
        response.setContentType("text/javascript;charset=UTF-8");
        String callback = request.getParameterValues(_callbackParam)[0];
        CometDOutput output = response.getOutput();
        output.write(callback.getBytes(response.getCharacterEncoding()));
        output.write(MESSAGE_BEGIN);
        return output;
    }

    @Override
    protected void endWrite(CometDResponse response, CometDOutput output) throws IOException {
        output.write(MESSAGE_END);
        output.close();
    }

    private boolean isCallbackValueValid(String[] callbackValues) {
        if (callbackValues == null || callbackValues.length != 1) {
            return false;
        }
        String callbackValue = callbackValues[0];
        return callbackValue.length() <= _callbackMaxLength && CALLBACK_PATTERN.matcher(callbackValue).matches();
    }
}
