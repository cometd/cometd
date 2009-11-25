// ========================================================================
// Copyright 2007 Dojo Foundation
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

package org.cometd;

/**
 * Remove Listener Interface.
 *
 * Objects implementing this interface may listen for client removal events
 * by calling the {@link Client#addListener(ClientListener)}
 *
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public interface RemoveListener extends ClientListener
{
    /**
     * This method is called after a client is removed.
     * @param clientId The clientId removed
     * @param timeout True if the client was removed due to a timeout.
     */
    public void removed(String clientId, boolean timeout);
}
