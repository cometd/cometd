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
package org.cometd.javascript;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

public interface ThreadModel {
    void init() throws Exception;

    void destroy() throws Exception;

    Object evaluate(URL url) throws IOException;

    Object evaluate(String scriptName, String script);

    Object invoke(boolean sync, Scriptable scope, Scriptable thiz, Function function, Object... arguments);

    Object invoke(boolean sync, Scriptable scope, Scriptable thiz, String functionName, Object... arguments);

    void define(Class<? extends Scriptable> clazz) throws InvocationTargetException, IllegalAccessException, InstantiationException;

    Object get(String name);

    void remove(String name);
}
