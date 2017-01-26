/*
 * Copyright (c) 2008-2017 the original author or authors.
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
package org.cometd.client.transport;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.common.AbstractTransport;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ClientTransport}s are used by {@link org.cometd.client.BayeuxClient} to send and receive Bayeux messages.
 */
public abstract class ClientTransport extends AbstractTransport {
    public static final String MAX_NETWORK_DELAY_OPTION = "maxNetworkDelay";
    public static final String JSON_CONTEXT_OPTION = "jsonContext";
    public static final String SCHEDULER_OPTION = "scheduler";

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private long maxNetworkDelay;
    private JSONContext.Client jsonContext;

    protected ClientTransport(String name, Map<String, Object> options) {
        super(name, options);
    }

    public void init() {
        Object option = getOption(JSON_CONTEXT_OPTION);
        if (option == null) {
            jsonContext = new JettyJSONContextClient();
        } else {
            if (option instanceof String) {
                try {
                    Class<?> jsonContextClass = Thread.currentThread().getContextClassLoader().loadClass((String)option);
                    if (JSONContext.Client.class.isAssignableFrom(jsonContextClass)) {
                        jsonContext = (JSONContext.Client)jsonContextClass.newInstance();
                    } else {
                        throw new IllegalArgumentException("Invalid implementation of " + JSONContext.Client.class.getName() + " provided: " + option);
                    }
                } catch (Exception x) {
                    throw new IllegalArgumentException("Invalid implementation of " + JSONContext.Client.class.getName() + " provided: " + option, x);
                }
            } else if (option instanceof JSONContext.Client) {
                jsonContext = (JSONContext.Client)option;
            } else {
                throw new IllegalArgumentException("Invalid implementation of " + JSONContext.Client.class.getName() + " provided: " + option);
            }
        }
        setOption(JSON_CONTEXT_OPTION, jsonContext);
    }

    /**
     * Aborts this transport, usually by cancelling all pending Bayeux messages that require a response,
     * such as {@code /meta/connect}s, without waiting for a response.
     *
     * @see org.cometd.client.BayeuxClient#abort()
     */
    public abstract void abort();

    /**
     * Terminates this transport, usually by closing network connections opened directly by this transport.
     *
     * @see org.cometd.client.BayeuxClient#disconnect()
     */
    public void terminate() {
    }

    public abstract boolean accept(String version);

    public abstract void send(TransportListener listener, List<Message.Mutable> messages);

    protected List<Message.Mutable> parseMessages(String content) throws ParseException {
        return new ArrayList<>(Arrays.asList(jsonContext.parse(content)));
    }

    protected String generateJSON(List<Message.Mutable> messages) {
        return jsonContext.generate(messages);
    }

    public long getMaxNetworkDelay() {
        return maxNetworkDelay = getOption(MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
    }

    protected void setMaxNetworkDelay(long maxNetworkDelay) {
        this.maxNetworkDelay = maxNetworkDelay;
    }

    public interface Factory {
        public ClientTransport newClientTransport(String url, Map<String, Object> options);
    }
}
