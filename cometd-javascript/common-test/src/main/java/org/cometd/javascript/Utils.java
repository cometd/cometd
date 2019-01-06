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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;

public class Utils {
    private Utils() {
    }

    public static Object jsToJava(Object jsObject) {
        return jsToJava(jsObject, new IdentityHashMap<Object, Boolean>());
    }

    private static Object jsToJava(Object jsObject, Map<Object, Boolean> identities) {
        Object existing = identities.put(jsObject, Boolean.TRUE);
        if (existing != null) {
            return jsObject;
        }
        if (jsObject == null) {
            return null;
        }
        if (jsObject == org.mozilla.javascript.Context.getUndefinedValue()) {
            return null;
        }
        if (jsObject instanceof String) {
            return jsObject;
        }
        if (jsObject instanceof Boolean) {
            return jsObject;
        }
        if (jsObject instanceof Integer) {
            return jsObject;
        }
        if (jsObject instanceof Long) {
            return jsObject;
        }
        if (jsObject instanceof Float) {
            return jsObject;
        }
        if (jsObject instanceof Double) {
            return jsObject;
        }
        if (jsObject instanceof NativeArray) {
            return convertArray((NativeArray)jsObject, identities);
        }
        if (jsObject instanceof NativeObject) {
            return convertObject((NativeObject)jsObject, identities);
        }
        if (jsObject instanceof NativeJavaObject) {
            return ((NativeJavaObject)jsObject).unwrap();
        }
        return jsObject;
    }

    private static Object[] convertArray(NativeArray jsArray, Map<Object, Boolean> identities) {
        Object[] ids = jsArray.getIds();
        Object[] result = new Object[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Object id = ids[i];
            int index = (Integer)id;
            Object jsValue = jsArray.get(index, jsArray);
            result[i] = jsToJava(jsValue, identities);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object convertObject(NativeObject jsObject, Map<Object, Boolean> identities) {
        Object[] ids = jsObject.getIds();
        Map result = new HashMap(ids.length);
        for (Object id : ids) {
            if (id instanceof String) {
                Object jsValue = jsObject.get((String)id, jsObject);
                result.put(id, jsToJava(jsValue, identities));
            } else if (id instanceof Integer) {
                Object jsValue = jsObject.get((Integer)id, jsObject);
                result.put(id, jsToJava(jsValue, identities));
            } else {
                throw new AssertionError();
            }
        }
        return result;
    }

    public static boolean isJavaScriptObject(Object object) {
        // Do not remove: called from JavaScript's env.js
        return object instanceof NativeObject;
    }
}
