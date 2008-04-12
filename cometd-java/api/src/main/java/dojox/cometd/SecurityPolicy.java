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

package dojox.cometd;

/* ------------------------------------------------------------ */
/** Pluggable security policy for Bayeux
 *
 */
public interface SecurityPolicy
{
    boolean canHandshake(Message message);
    boolean canCreate(Client client,String channel,Message message);
    boolean canSubscribe(Client client,String channel,Message messsage);
    boolean canPublish(Client client,String channel,Message messsage);
}
