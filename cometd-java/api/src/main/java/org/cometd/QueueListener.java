// ========================================================================
// Copyright 2008 Dojo Foundation
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
//========================================================================

package org.cometd;

import org.cometd.Client;
import org.cometd.ClientListener;
import org.cometd.Message;

/**
 * @author athena
 *
 */
public interface QueueListener extends ClientListener
{
    /* ------------------------------------------------------------ */
    /**
     * Call back to notify if a message for a client will result in the
     * message queue exceeding {@link Client#getMaxQueue()}.
     * This is called with the client instance locked, so it is safe for the
     * handler to manipulate the queue returned by {@link Client#getQueue()}, but 
     * action in the callback that may result in another Client instance should be 
     * avoided as that would risk deadlock.
     * @param from Client message is published from
     * @param to Client message is being delivered to
     * @param message
     * @return true if the message should be added to the client queue
     */
    public boolean queueMaxed(Client from, Client to, Message message);
}
