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

package org.cometd.client.transport;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.common.AbstractTransport;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContext;

public abstract class ClientTransport extends AbstractTransport
{
    public static final String TIMEOUT_OPTION = "timeout";
    public static final String INTERVAL_OPTION = "interval";
    public static final String MAX_NETWORK_DELAY_OPTION = "maxNetworkDelay";
    public static final String JSON_CONTEXT = "jsonContext";

    private JSONContext<Message.Mutable> jsonContext;

    protected ClientTransport(String name, Map<String, Object> options)
    {
        super(name, options);
    }

    public void init()
    {
        jsonContext = (JSONContext<Message.Mutable>)getOption(JSON_CONTEXT);
        if (jsonContext == null)
            jsonContext = new JettyJSONContext();
    }

    public abstract void abort();

    public abstract void reset();

    public abstract boolean accept(String version);

    public abstract void send(TransportListener listener, Message.Mutable... messages);

    protected List<Message.Mutable> parseMessages(String content)
    {
        return Arrays.asList(jsonContext.parse(content));
    }

    protected String generateJSON(Message.Mutable[] messages)
    {
        return jsonContext.generate(messages);
    }
}
