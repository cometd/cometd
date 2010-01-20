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
package org.cometd;


/**
 * Cometd extension interface.
 * <p>
 * This interface is used both for server extensions and for client
 * extensions.
 * 
 * @see Bayeux#addExtension(Extension)
 * @see Client#addExtension(Extension)
 *
 */
public interface Extension
{
    /**
     * @param from
     * @param message
     * @return modified message or null to discard message
     */
    Message rcv(Client from, Message message);
    
    /**
     * @param from
     * @param message
     * @return modified message
     */
    Message rcvMeta(Client from, Message message);
    
    /**
     * @param from
     * @param message
     * @return modified message or null to discard message
     */
    Message send(Client from, Message message);
    
    /**
     * @param from
     * @param message
     * @return modified message
     */
    Message sendMeta(Client from, Message message);
}
