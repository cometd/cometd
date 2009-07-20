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

package org.cometd;


/**
 * Data filters are used to transform data as it is sent to a {@link Channel}.
 *
 * @version $Revision: 686 $ $Date: 2009-07-03 11:07:24 +0200 (Fri, 03 Jul 2009) $
 */
public interface DataFilter
{
    /**
     * @param from the {@link Client} that sends the data
     * @param channel the channel the data is being sent to
     * @param data the data being sent
     * @return the transformed data
     * @throws IllegalStateException If the message should be aborted
     */
    public Object filter(Client from, Channel channel, Object data) throws IllegalStateException;
}
