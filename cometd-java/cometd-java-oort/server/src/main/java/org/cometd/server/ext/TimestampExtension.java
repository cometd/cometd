// ========================================================================
// Copyright 2006 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cometd.server.ext;

import java.util.TimeZone;

import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.server.AbstractBayeux;
import org.eclipse.jetty.util.DateCache;

public class TimestampExtension implements Extension
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

    public Message rcv(Client from, Message message)
    {
        return message;
    }

    public Message rcvMeta(Client from, Message message)
    {
        return message;
    }

    public Message send(Client from, Message message)
    {
        message.put(AbstractBayeux.TIMESTAMP_FIELD,_dateCache.format(System.currentTimeMillis()));
        return message;
    }

    public Message sendMeta(Client from, Message message)
    {
        message.put(AbstractBayeux.TIMESTAMP_FIELD,_dateCache.format(System.currentTimeMillis()));
        return message;
    }

}
