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
package org.cometd.common;

import java.text.ParseException;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;

public class JacksonJSONContextClient extends JacksonJSONContext<Message.Mutable, HashMapMessage> implements JSONContext.Client {
    @Override
    protected Class<HashMapMessage[]> rootArrayClass() {
        return HashMapMessage[].class;
    }

    @Override
    public boolean supportsNonBlockingParser() {
        return true;
    }
}
