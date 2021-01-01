/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import java.util.Map;

public class TransportException extends RuntimeException {
    private final Map<String, Object> fields;

    public TransportException(Map<String, Object> fields) {
        this(null, fields);
    }

    public TransportException(Throwable cause, Map<String, Object> fields) {
        super(String.valueOf(fields), cause);
        this.fields = fields;
    }

    public Map<String, Object> getFields() {
        return fields;
    }
}
