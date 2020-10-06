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
package org.cometd.client.transport;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.common.AbstractTransport;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;

/**
 * {@link ClientTransport}s are used by {@link org.cometd.client.BayeuxClient} to send and receive Bayeux messages.
 */
public abstract class ClientTransport extends AbstractTransport {
    public static final String URL_OPTION = "url";
    public static final String MAX_NETWORK_DELAY_OPTION = "maxNetworkDelay";
    public static final String JSON_CONTEXT_OPTION = "jsonContext";
    public static final String SCHEDULER_OPTION = "scheduler";
    public static final String MAX_MESSAGE_SIZE_OPTION = "maxMessageSize";

    private String url;
    private ScheduledExecutorService scheduler;
    private SchedulerSource schedulerSource = SchedulerSource.UNKNOWN;
    private long maxNetworkDelay;
    private JSONContext.Client jsonContext;

    @Deprecated
    protected ClientTransport(String name, String url, Map<String, Object> options) {
        this(name, url, options, null);
    }

    protected ClientTransport(String name, String url, Map<String, Object> options, ScheduledExecutorService scheduler) {
        super(name, options);
        this.url = url;
        this.scheduler = scheduler;
        if (scheduler != null) {
            this.schedulerSource = SchedulerSource.PROVIDED;
        }
    }

    public String getURL() {
        return url;
    }

    public void setURL(String url) {
        this.url = url;
    }

    public void init() {
        Object urlOption = getOption(URL_OPTION);
        if (url == null) {
            url = (String)urlOption;
        }

        Object jsonContextOption = getOption(JSON_CONTEXT_OPTION);
        if (jsonContextOption == null) {
            jsonContext = new JettyJSONContextClient();
        } else {
            if (jsonContextOption instanceof String) {
                try {
                    Class<?> jsonContextClass = Thread.currentThread().getContextClassLoader().loadClass((String)jsonContextOption);
                    if (JSONContext.Client.class.isAssignableFrom(jsonContextClass)) {
                        jsonContext = (JSONContext.Client)jsonContextClass.getConstructor().newInstance();
                    } else {
                        throw new IllegalArgumentException("Invalid implementation of " + JSONContext.Client.class.getName() + " provided: " + jsonContextOption);
                    }
                } catch (Exception x) {
                    throw new IllegalArgumentException("Invalid implementation of " + JSONContext.Client.class.getName() + " provided: " + jsonContextOption, x);
                }
            } else if (jsonContextOption instanceof JSONContext.Client) {
                jsonContext = (JSONContext.Client)jsonContextOption;
            } else {
                throw new IllegalArgumentException("Invalid implementation of " + JSONContext.Client.class.getName() + " provided: " + jsonContextOption);
            }
        }
        setOption(JSON_CONTEXT_OPTION, jsonContext);
    }

    protected JSONContext.Client getJSONContextClient() {
        return jsonContext;
    }

    protected void initScheduler() {
        if (scheduler == null) {
            scheduler = (ScheduledExecutorService)getOption(SCHEDULER_OPTION);
            schedulerSource = SchedulerSource.SHARED;
            if (scheduler == null) {
                scheduler = new BayeuxClient.Scheduler(1);
                schedulerSource = SchedulerSource.INTERNAL;
            }
        }
    }

    protected void shutdownScheduler() {
        if (schedulerSource == SchedulerSource.INTERNAL) {
            scheduler.shutdown();
        }
        if (schedulerSource != SchedulerSource.PROVIDED) {
            scheduler = null;
            schedulerSource = SchedulerSource.UNKNOWN;
        }
    }

    protected ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    /**
     * Aborts this transport, usually by cancelling all pending Bayeux messages that require a response,
     * such as {@code /meta/connect}s, without waiting for a response.
     *
     * @see org.cometd.client.BayeuxClient#abort()
     * @param failure the cause of the abort
     */
    public abstract void abort(Throwable failure);

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

    public static class FailureInfo {
        public ClientTransport transport;
        public Throwable cause;
        public String error;
        public String action;
        public String url;
        public long delay;

        public BayeuxClient.State actionToState() {
            switch (action) {
                case Message.RECONNECT_HANDSHAKE_VALUE:
                    return BayeuxClient.State.REHANDSHAKING;
                case Message.RECONNECT_NONE_VALUE:
                    return BayeuxClient.State.TERMINATING;
                default:
                    return BayeuxClient.State.UNCONNECTED;
            }
        }

        @Override
        public String toString() {
            return String.format("%s@%x[transport=%s,cause=%s,action=%s]",
                    getClass().getSimpleName(),
                    hashCode(),
                    transport,
                    cause,
                    action);
        }
    }

    public interface FailureHandler {
        void handle(FailureInfo failureInfo);
    }

    private enum SchedulerSource {
        UNKNOWN, PROVIDED, SHARED, INTERNAL
    }
}
