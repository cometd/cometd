/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.server.filter;

import java.util.regex.Pattern;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;

public class NoScriptsFilter extends JSONDataFilter {
    private static Pattern __scriptBegin = Pattern.compile("<\\s*[Ss][Cc][Rr][Ii][Pp][Tt]");
    private static Pattern __scriptEnd = Pattern.compile("[Ss][Cc][Rr][Ii][Pp][Tt]\\s*>");

    @Override
    protected Object filterString(ServerSession session, ServerChannel channel, String string) {
        string = __scriptBegin.matcher(string).replaceAll("<span");
        return __scriptEnd.matcher(string).replaceAll("span>");
    }
}
