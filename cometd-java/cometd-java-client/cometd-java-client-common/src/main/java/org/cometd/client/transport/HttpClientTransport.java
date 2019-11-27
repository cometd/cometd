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
package org.cometd.client.transport;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.Map;

public abstract class HttpClientTransport extends ClientTransport {
    private volatile CookieStore cookieStore;

    protected HttpClientTransport(String name, String url, Map<String, Object> options) {
        super(name, url, options);
    }

    protected CookieStore getCookieStore() {
        return cookieStore;
    }

    public void setCookieStore(CookieStore cookieStore) {
        this.cookieStore = cookieStore;
    }

    protected List<HttpCookie> getCookies(URI uri) {
        return getCookieStore().get(uri);
    }

    protected void storeCookies(URI uri, Map<String, List<String>> cookies) {
        try {
            CookieManager cookieManager = new CookieManager(getCookieStore(), CookiePolicy.ACCEPT_ALL);
            cookieManager.put(uri, cookies);
        } catch (IOException x) {
            if (logger.isDebugEnabled()) {
                logger.debug("Could not parse cookies", x);
            }
        }
    }
}
