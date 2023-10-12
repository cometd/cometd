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
package org.cometd.server.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDRequest;
import org.cometd.server.CometDResponse;
import org.cometd.server.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JSONPHttpTransport extends AbstractHttpTransport {
    public final static String NAME = "callback-polling";
    public final static String CALLBACK_PARAMETER_OPTION = "callbackParameter";
    public final static String CALLBACK_PARAMETER_MAX_LENGTH_OPTION = "callbackParameterMaxLength";

    private static final Logger LOGGER = LoggerFactory.getLogger(JSONHttpTransport.class);
    private final static String PREFIX = "long-polling.jsonp";
    private final static Pattern CALLBACK_PATTERN = Pattern.compile("^[a-zA-Z0-9._\\-]+$");
    private final static byte[] MESSAGE_BEGIN = new byte[]{'(', '['};
    private final static byte[] MESSAGE_END = new byte[]{']', ')'};

    private String callbackParam = "jsonp";
    private int callbackMaxLength = 64;

    public JSONPHttpTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init() {
        super.init();
        callbackParam = getOption(CALLBACK_PARAMETER_OPTION, callbackParam);
        callbackMaxLength = getOption(CALLBACK_PARAMETER_MAX_LENGTH_OPTION, callbackMaxLength);
        // This transport must deliver only via /meta/connect
        setMetaConnectDeliveryOnly(true);
    }

    public String getCallbackParameter() {
        return callbackParam;
    }

    public int getCallbackMaxLength() {
        return callbackMaxLength;
    }

    @Override
    public boolean accept(CometDRequest request) {
        String[] callbackValue = request.getParameterValues(getCallbackParameter());
        return "GET".equals(request.getMethod()) && isCallbackValueValid(callbackValue);
    }

    private boolean isCallbackValueValid(String[] callbackValues) {
        if (callbackValues == null || callbackValues.length != 1) {
            return false;
        }
        String callbackValue = callbackValues[0];
        return callbackValue.length() <= getCallbackMaxLength() && CALLBACK_PATTERN.matcher(callbackValue).matches();
    }

    @Override
    protected void handle(TransportContext context) {
        try {
            String[] requestParameters = context.request().getParameterValues(MESSAGE_PARAM);

            if (requestParameters == null || requestParameters.length == 0) {
                throw new IOException("Missing '" + MESSAGE_PARAM + "' request parameter");
            }

            List<ServerMessage.Mutable> messages;
            if (requestParameters.length == 1) {
                ServerMessage.Mutable[] parsed = parseMessages(requestParameters[0]);
                messages = parsed == null ? List.of() : List.of(parsed);
            } else {
                messages = new ArrayList<>();
                for (String batch : requestParameters) {
                    if (batch == null) {
                        continue;
                    }
                    ServerMessage.Mutable[] parsed = parseMessages(batch);
                    if (parsed != null) {
                        messages.addAll(List.of(parsed));
                    }
                }
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Parsed {} messages", messages.size());
            }

            processMessages(context, messages);
        } catch (ParseException x) {
            LOGGER.warn("Could not parse JSON: " + x.getMessage(), x.getMessage());
            context.promise().fail(new HttpException(400, x.getCause()));
        } catch (Throwable x) {
            context.promise().fail(x);
        }
    }

    @Override
    protected void writePrepare(TransportContext context, Promise<Void> promise) {
        CometDResponse response = context.response();
        response.setContentType("text/javascript;charset=UTF-8");
        String callback = context.request().getParameterValues(getCallbackParameter())[0];
        response.getOutput().write(false, callback.getBytes(StandardCharsets.UTF_8), promise);
    }

    @Override
    protected void writeBegin(CometDResponse.Output output, Promise<Void> promise) {
        output.write(false, MESSAGE_BEGIN, promise);
    }

    @Override
    protected void writeEnd(CometDResponse.Output output, Promise<Void> promise) {
        output.write(true, MESSAGE_END, promise);
    }
}
