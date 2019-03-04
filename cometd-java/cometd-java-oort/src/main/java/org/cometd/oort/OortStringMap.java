/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.oort;

import java.util.concurrent.ConcurrentMap;

/**
 * The equivalent of an {@code OortMap&lt;String,V&gt;}.
 *
 * @param <V> the value type
 */
public class OortStringMap<V> extends OortMap<String, V> {
    public OortStringMap(Oort oort, String name, Factory<ConcurrentMap<String, V>> factory) {
        super(oort, name, factory);
    }
}
