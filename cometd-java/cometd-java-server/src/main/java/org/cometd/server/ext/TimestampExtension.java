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

package org.cometd.server.ext;

import java.util.TimeZone;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.DateCache;

public class TimestampExtension extends Extension.Adapter
{
    private final DateCache _dateCache;

    public TimestampExtension()
    {
        _dateCache=new DateCache();
        _dateCache.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public TimestampExtension(String format)
    {
        _dateCache=new DateCache(format);
        _dateCache.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public TimestampExtension(String format, TimeZone tz)
    {
        _dateCache=new DateCache(format);
        _dateCache.setTimeZone(tz);
    }

    @Override
    public boolean send(ServerSession from, ServerSession to, Mutable message)
    {
        message.put(Message.TIMESTAMP_FIELD,_dateCache.format(System.currentTimeMillis()));
        return true;
    }

    @Override
    public boolean sendMeta(ServerSession to, Mutable message)
    {
        message.put(Message.TIMESTAMP_FIELD,_dateCache.format(System.currentTimeMillis()));
        return true;
    }
}
