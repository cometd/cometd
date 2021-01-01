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
package org.cometd.server.filter;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;

/**
 * <p>A filter that can add, modify or remove fields from the
 * {@link Message#getData() message data}.</p>
 */
public interface DataFilter {
    /**
     * <p>Modifies the given message data.</p>
     * <p>Returning {@code null} or throwing {@link AbortException}
     * results in the message processing being interrupted
     * and the message itself discarded.</p>
     * <p>If the returned object is different (as returned by
     * the {@code !=} operator) from the {@code data} parameter
     * then it is set as the new message data via
     * {@link Message.Mutable#setData(Object)}.</p>
     *
     * @param session the {@link ServerSession} that sends the data
     * @param channel the channel the data is being sent on
     * @param data    the data being sent
     * @return the transformed data or null if the message should be ignored
     * @throws AbortException to abort the filtering of the data
     */
    public abstract Object filter(ServerSession session, ServerChannel channel, Object data) throws AbortException;

    /**
     * <p>Aborts the filtering of the message data.</p>
     */
    public static class AbortException extends RuntimeException {
        public AbortException() {
        }

        public AbortException(String message) {
            super(message);
        }

        public AbortException(String message, Throwable cause) {
            super(message, cause);
        }

        public AbortException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * @deprecated use {@link AbortException} instead
     */
    @Deprecated
    public class Abort extends AbortException {
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
