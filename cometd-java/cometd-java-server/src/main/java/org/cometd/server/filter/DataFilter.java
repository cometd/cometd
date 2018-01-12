/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;

public interface DataFilter {
    /**
     * @param from    the {@link ServerSession} that sends the data
     * @param channel the channel the data is being sent to
     * @param data    the data being sent
     * @return the transformed data or null if the message should be aborted
     */
    public abstract Object filter(ServerSession from, ServerChannel channel, Object data);


    /**
     * Abort the message by throwing this exception
     */
    public class Abort extends RuntimeException {
        public Abort() {
            super();
        }

        public Abort(String msg) {
            super(msg);
        }

        public Abort(String msg, Throwable cause) {
            super(msg, cause);
        }

    }
}
