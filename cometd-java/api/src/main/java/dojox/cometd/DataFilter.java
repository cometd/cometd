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

package dojox.cometd;


/* ------------------------------------------------------------ */
/** Data Filter
 * Data filters are used to transform data as it is sent to a Channel.
 * 
 */
public interface DataFilter
{
    /**
     * @param from
     * @param to TODO
     * @param data
     * @return The filtered data.
     * @throws IllegalStateException If the message should be aborted
     */
    Object filter(Client from, Channel to, Object data) throws IllegalStateException;
}
