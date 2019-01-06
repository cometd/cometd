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

import org.mozilla.javascript.ScriptableObject;

public class SessionStorage extends ScriptableObject {
    private Map<String, String> store;

    @Override
    public String getClassName() {
        return "SessionStorage";
    }

    public void setStore(Map<String, String> store) {
        this.store = store;
    }

    public String jsFunction_getItem(String key) {
        return store.get(key);
    }

    public void jsFunction_setItem(String key, String value) {
        store.put(key, value);
    }

    public void jsFunction_removeItem(String key) {
        store.remove(key);
    }
}
