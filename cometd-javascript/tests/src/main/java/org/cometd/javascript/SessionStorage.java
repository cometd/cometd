/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.javascript;

import java.util.Map;

/**
 * <p>Representation of the {@code window.sessionStorage} object in the JavaScript environment.</p>
 * <p>The actual store must survive page reloads.</p>
 */
public class SessionStorage {
    private final Map<String, String> store;

    public SessionStorage(Map<String, String> store) {
        this.store = store;
    }

    public String getItem(String key) {
        return store.get(key);
    }

    public void setItem(String key, String value) {
        store.put(key, value);
    }

    public void removeItem(String key) {
        store.remove(key);
    }
}
