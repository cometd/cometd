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
package org.cometd.benchmark;

public class Config {
    public static final String CONTEXT_PATH = "/cometd";
    public static final String SERVLET_PATH = "/cometd";
    public static final String ID_FIELD = "ID";
    public static final String CHANNEL_PREFIX = "/bench/";

    public static final long META_CONNECT_TIMEOUT = 20000;
    public static final long MAX_NETWORK_DELAY = 5000;

    private Config() {
    }
}
