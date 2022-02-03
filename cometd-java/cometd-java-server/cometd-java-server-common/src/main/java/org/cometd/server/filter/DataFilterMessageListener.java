/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.server.filter;

import java.util.Arrays;
import java.util.List;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A MessageListener that applies DataFilters to the received messages.</p>
 */
public class DataFilterMessageListener implements ServerChannel.MessageListener {
    private final Logger _logger = LoggerFactory.getLogger(getClass());
    private final List<DataFilter> _filters;

    public DataFilterMessageListener(DataFilter... filters) {
        this(null, filters);
    }

    public DataFilterMessageListener(BayeuxServer bayeux, DataFilter... filters) {
        _filters = Arrays.asList(filters);
    }

    @Override
    public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
        try {
            Object data = message.getData();
            Object orig = data;
            for (DataFilter filter : _filters) {
                data = filter.filter(from, channel, data);
                if (data == null) {
                    return false;
                }
            }
            if (data != orig) {
                message.setData(data);
            }
            return true;
        } catch (DataFilter.AbortException a) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("", a);
            }
            return false;
        }
    }
}
