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
//========================================================================
//
package org.cometd.bayeux;

import org.cometd.bayeux.client.MetaMessage;

/**
 * @version $Revision$ $Date: 2009-12-07 23:42:45 +0100 (Mon, 07 Dec 2009) $
 */
public interface Extension
{
    Message.Mutable incoming(Message.Mutable message);

    MetaMessage.Mutable metaIncoming(MetaMessage.Mutable metaMessage);

    Message.Mutable outgoing(Message.Mutable message);

    MetaMessage.Mutable metaOutgoing(MetaMessage.Mutable metaMessage);

    public static class Adapter implements Extension
    {
        public Message.Mutable incoming(Message.Mutable message)
        {
            return message;
        }

        public MetaMessage.Mutable metaIncoming(MetaMessage.Mutable metaMessage)
        {
            return metaMessage;
        }

        public Message.Mutable outgoing(Message.Mutable message)
        {
            return message;
        }

        public MetaMessage.Mutable metaOutgoing(MetaMessage.Mutable metaMessage)
        {
            return metaMessage;
        }
    }
}
