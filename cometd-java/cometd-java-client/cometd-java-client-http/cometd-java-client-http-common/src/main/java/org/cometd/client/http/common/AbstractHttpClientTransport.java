/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.client.http.common;

import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.common.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractHttpClientTransport extends HttpClientTransport {
    public static final String NAME = "long-polling";
    public static final String PREFIX = "long-polling.json";
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractHttpClientTransport.class);

    private boolean _aborted;
    private int _maxMessageSize;
    private boolean _appendMessageType;
    private Map<String, Object> _advice;

    /**
     * @param url the CometD server URL
     * @param options the transport options
     * @deprecated use {@link #AbstractHttpClientTransport(String, Map, ScheduledExecutorService)} instead
     */
    @Deprecated
    protected AbstractHttpClientTransport(String url, Map<String, Object> options) {
        this(url, options, null);
    }

    protected AbstractHttpClientTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler) {
        super(NAME, url, options, scheduler);
        setOptionPrefix(PREFIX);
    }

    @Override
    public boolean accept(String bayeuxVersion) {
        return true;
    }

    @Override
    public void init() {
        super.init();
        _aborted = false;
        _maxMessageSize = getOption(ClientTransport.MAX_MESSAGE_SIZE_OPTION, 1024 * 1024);

        Pattern uriRegexp = Pattern.compile("(^https?://(((\\[[^]]+])|([^:/?#]+))(:(\\d+))?))?([^?#]*)(.*)?");
        Matcher uriMatcher = uriRegexp.matcher(getURL());
        if (uriMatcher.matches()) {
            String afterPath = uriMatcher.group(9);
            _appendMessageType = afterPath == null || afterPath.trim().length() == 0;
        }

        initScheduler();
    }

    @Override
    public void terminate() {
        shutdownScheduler();
        super.terminate();
    }

    @Override
    public void abort(Throwable failure) {
        _aborted = true;
    }

    protected boolean isAborted() {
        return _aborted;
    }

    public int getMaxMessageSize() {
        return _maxMessageSize;
    }

    protected boolean isAppendMessageType() {
        return _appendMessageType;
    }

    protected String newRequestURI(List<Message.Mutable> messages) {
        String url = getURL();
        if (isAppendMessageType() && messages.size() == 1) {
            Message.Mutable message = messages.get(0);
            if (message.isMeta()) {
                String type = message.getChannel().substring(Channel.META.length());
                if (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                url += type;
            }
        }
        return url;
    }

    protected Map<String, Object> getAdvice() {
        return _advice;
    }

    protected void setAdvice(Map<String, Object> advice) {
        _advice = advice;
    }

    protected long calculateMaxNetworkDelay(List<Message.Mutable> messages) {
        long maxNetworkDelay = getMaxNetworkDelay();
        if (messages.size() == 1) {
            Message.Mutable message = messages.get(0);
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                Map<String, Object> advice = message.getAdvice();
                if (advice == null) {
                    advice = getAdvice();
                }
                if (advice != null) {
                    Object timeout = advice.get("timeout");
                    if (timeout instanceof Number) {
                        maxNetworkDelay += ((Number)timeout).longValue();
                    } else if (timeout != null) {
                        maxNetworkDelay += Long.parseLong(timeout.toString());
                    }
                }
            }
        }
        return maxNetworkDelay;
    }

    protected void processResponseContent(TransportListener listener, List<Message.Mutable> requestMessages, String content) {
        if (content != null && content.length() > 0) {
            try {
                List<Message.Mutable> messages = parseMessages(content);
                processResponseMessages(listener, messages);
            } catch (ParseException x) {
                listener.onFailure(x, requestMessages);
            }
        } else {
            Map<String, Object> failure = new HashMap<>(2);
            // Convert the 200 into 204 (no content)
            failure.put("httpCode", 204);
            TransportException x = new TransportException(failure);
            listener.onFailure(x, requestMessages);
        }
    }

    protected void processResponseMessages(TransportListener listener, List<Message.Mutable> messages) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Received messages {}", messages);
        }
        for (Message.Mutable message : messages) {
            if (message.isSuccessful() && Channel.META_CONNECT.equals(message.getChannel())) {
                Map<String, Object> advice = message.getAdvice();
                if (advice != null && advice.containsKey("timeout")) {
                    setAdvice(advice);
                }
            }
        }
        listener.onMessages(messages);
    }

    protected void processWrongResponseCode(TransportListener listener, List<Message.Mutable> messages, int code) {
        Map<String, Object> failure = new HashMap<>(2);
        failure.put("httpCode", code);
        TransportException x = new TransportException(failure);
        listener.onFailure(x, messages);
    }
}
