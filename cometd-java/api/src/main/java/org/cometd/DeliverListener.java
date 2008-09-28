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

import java.util.Queue;

import org.cometd.Client;
import org.cometd.ClientListener;
import org.cometd.Message;

/**
 * @author athena
 *
 */
public interface DeliverListener extends ClientListener
{
    /* ------------------------------------------------------------ */
    /**
     * callback to notify that the queue is about to be sent to the
     * client.  This is the last chance to process the queue and remove 
     * duplicates or merge messages.
     */
    public void deliver(Client client, Queue<Message> queue);
}
