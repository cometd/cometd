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
package org.cometd.client.ext;

import java.util.HashMap;
import java.util.Map;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSession.Extension;

public class TimesyncClientExtension implements Extension {
    private volatile int _lag;
    private volatile int _offset;

    @Override
    public boolean rcvMeta(ClientSession session, Mutable message) {
        Map<String, Object> ext = message.getExt(false);
        if (ext != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sync = (Map<String, Object>)ext.get("timesync");
            if (sync != null) {
                long now = System.currentTimeMillis();

                final long tc = ((Number)sync.get("tc")).longValue();
                final long ts = ((Number)sync.get("ts")).longValue();
                final int p = ((Number)sync.get("p")).intValue();
                // final int a=((Number)sync.get("a")).intValue();

                int l2 = (int)((now - tc - p) / 2);
                int o2 = (int)(ts - tc - l2);

                _lag = _lag == 0 ? l2 : (_lag + l2) / 2;
                _offset = _offset == 0 ? o2 : (_offset + o2) / 2;
            }
        }

        return true;
    }

    @Override
    public boolean sendMeta(ClientSession session, Mutable message) {
        Map<String, Object> ext = message.getExt(true);
        long now = System.currentTimeMillis();
        Map<String, Object> timesync = new HashMap<>(3);
        timesync.put("tc", now);
        timesync.put("l", _lag);
        timesync.put("o", _offset);
        ext.put("timesync", timesync);
        return true;
    }

    public int getOffset() {
        return _offset;
    }

    public int getLag() {
        return _lag;
    }

    public long getServerTime() {
        return System.currentTimeMillis() + _offset;
    }
}
