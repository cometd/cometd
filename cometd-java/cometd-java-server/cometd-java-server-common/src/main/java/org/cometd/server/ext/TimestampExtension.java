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
package org.cometd.server.ext;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;

public class TimestampExtension implements Extension {
    private final DateTimeFormatter formatter;

    public TimestampExtension() {
        this("EEE MMM dd HH:mm:ss zzz yyyy");
    }

    public TimestampExtension(String format) {
        this(format, TimeZone.getDefault());
    }

    public TimestampExtension(String format, TimeZone tz) {
        formatter = DateTimeFormatter.ofPattern(format).withZone(tz.toZoneId());
    }

    @Override
    public boolean send(ServerSession from, ServerSession to, Mutable message) {
        timestamp(message);
        return true;
    }

    @Override
    public boolean sendMeta(ServerSession to, Mutable message) {
        timestamp(message);
        return true;
    }

    private void timestamp(Mutable message) {
        message.put(Message.TIMESTAMP_FIELD, formatter.format(Instant.now()));
    }
}
