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

import java.util.Map;

/**
 * A Bayeux Message.
 * A Map of String to Object that has been optimized for conversion to JSON messages.
 * Even if this class implements {@link Cloneable}, it is not supported to deep clone
 * a message (mostly due to the fact that {@link Object#clone()} is broken in Java).
 */
public interface Message extends Map<String,Object>, Cloneable
{
    /**
     * Convenience method for <code>message.get(Bayeux.CLIENT_FIELD)</code>.
     * @return The clientId field of this message
     */
    public String getClientId();

    /**
     * Convenience method for <code>message.get(Bayeux.CHANNEL_FIELD)</code>.
     * @return The channel field of this message
     */
    public String getChannel();

    /**
     * Convenience method for <code>message.get(Bayeux.ID_FIELD)</code>.
     * @return The id field of this message
     */
    public String getId();

    /**
     * Convenience method for <code>message.get(Bayeux.DATA_FIELD)</code>.
     * @return The data field of this message
     */
    public Object getData();

    /**
     * Returns the <code>ext</code> field of this message, optionally creating it.
     * @param create whether the ext field should be created if it is absent.
     * @return The ext field of this message
     */
    public Map<String,Object> getExt(boolean create);

    /**
     * When this message represent a response to a request message, this method
     * returns the request message.
     * @return The message associated with this message
     */
    public Message getAssociated();

    /**
     * @return A shallow copy of this message.
     * @deprecated If you want a deep copy of a message, consider instantiating a new
     * message object and populating it with deep copies of the fields of this message.
     * A quicker alternative in case the fields of the messages are not known a priori
     * or not easily deep copyable, is to convert this message to JSON and then back to
     * a Message, functionality that is present in the Bayeux implementation.
     */
    @Deprecated
    public Object clone();
}
