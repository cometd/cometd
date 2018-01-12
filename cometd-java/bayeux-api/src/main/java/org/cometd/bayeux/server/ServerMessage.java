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
package org.cometd.bayeux.server;

import org.cometd.bayeux.Message;

/**
 * <p>Representation of a server side message.</p>
 */
public interface ServerMessage extends Message {
    /**
     * @return a message associated with this message on the server. Typically
     * this is a meta message that the current message is being sent in response
     * to.
     */
    ServerMessage.Mutable getAssociated();

    /**
     * @return true if the message is lazy and should not force the session's queue to be flushed
     */
    boolean isLazy();

    /**
     * The mutable version of a {@link ServerMessage}
     */
    public interface Mutable extends ServerMessage, Message.Mutable {
        /**
         * @param message the message associated with this message
         */
        void setAssociated(ServerMessage.Mutable message);

        /**
         * A lazy message does not provoke immediately delivery to the client
         * but it will be delivered at first occasion or after a timeout expires
         *
         * @param lazy whether the message is lazy
         */
        void setLazy(boolean lazy);
    }
}
