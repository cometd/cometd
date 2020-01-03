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
package org.cometd.client.ext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;

public class TimestampClientExtension implements ClientSession.Extension {
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneId.of("GMT"));

    @Override
    public boolean send(ClientSession session, Message.Mutable message) {
        addTimestamp(message);
        return true;
    }

    @Override
    public boolean sendMeta(ClientSession session, Message.Mutable message) {
        addTimestamp(message);
        return true;
    }

    private void addTimestamp(Message.Mutable message) {
        message.put(Message.TIMESTAMP_FIELD, formatter.format(Instant.now()));
    }
}
