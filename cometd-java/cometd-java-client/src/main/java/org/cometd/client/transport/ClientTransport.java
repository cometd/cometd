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

import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.common.AbstractTransport;
import org.cometd.common.HashMapMessage;

public abstract class ClientTransport extends AbstractTransport
{
    public final static String TIMEOUT_OPTION = "timeout";
    public final static String INTERVAL_OPTION = "interval";
    public final static String MAX_NETWORK_DELAY_OPTION = "maxNetworkDelay";

    protected ClientTransport(String name, Map<String, Object> options)
    {
        super(name, options);
    }

    public void init()
    {
    }

    public abstract void abort();

    public abstract void reset();

    public abstract boolean accept(String version);

    public abstract void send(TransportListener listener, Message.Mutable... messages);

    protected List<Message.Mutable> parseMessages(String content)
    {
        // Easy to replace with JSONParser.parse();
        // where the JSONParser is a transport option

        return HashMapMessage.parseMessages(content);
    }

    protected String generateJSON(Message.Mutable[] messages)
    {
        // TODO: replace this with a JSONGenerator.generate()

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < messages.length; ++i)
        {
            if (i > 0)
                json.append(",");
            Message message = messages[i];
            json.append(message.getJSON());
        }
        json.append("]");
        return json.toString();
    }
}
