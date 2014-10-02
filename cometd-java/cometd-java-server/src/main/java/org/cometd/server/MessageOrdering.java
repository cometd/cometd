/*
 * Copyright (c) 2008-2014 the original author or authors.
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
package org.cometd.server;

import java.util.Comparator;
import java.util.List;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

public class MessageOrdering implements Comparator<ServerMessage.Mutable> {
    public static final Comparator<ServerMessage.Mutable> COMPARATOR = new MessageOrdering();

    private static final List<String> CHANNEL_ORDERING = unmodifiableList(asList(Channel.META_HANDSHAKE, Channel.META_CONNECT));
    private static final int AFTER_ALL_RELEVANT_MESSAGES = CHANNEL_ORDERING.size();

    private MessageOrdering() { }

    @Override
    public int compare(ServerMessage.Mutable messageA, ServerMessage.Mutable messageB) {
        return Integer.compare(relativePositionOf(messageA), relativePositionOf(messageB));
    }

    private static int relativePositionOf(ServerMessage.Mutable message) {
        int index = CHANNEL_ORDERING.indexOf(message.getChannel());
        if (index >= 0) {
            return index;
        }
        return AFTER_ALL_RELEVANT_MESSAGES;
    }
}
