/*
 * Copyright (c) 2008-2022 the original author or authors.
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
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookieStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpClientTransport extends ClientTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpClientTransport.class);

    private volatile HttpCookieStore cookieStore;

    @Deprecated
    protected HttpClientTransport(String name, String url, Map<String, Object> options) {
        this(name, url, options, null);
    }

    protected HttpClientTransport(String name, String url, Map<String, Object> options, ScheduledExecutorService scheduler) {
        super(name, url, options, scheduler);
    }

    protected HttpCookieStore getHttpCookieStore() {
        return cookieStore;
    }

    public void setHttpCookieStore(HttpCookieStore cookieStore) {
        this.cookieStore = cookieStore;
    }

    protected List<HttpCookie> getCookies(URI uri) {
        return getHttpCookieStore().match(uri);
    }

    protected void storeCookies(URI uri, Map<String, List<String>> cookies) {
        // TODO: change this old API that uses java.net to use Jetty 12 cookie APIs.
        try {
            Store store = new Store();
            CookieManager cookieManager = new CookieManager(store, CookiePolicy.ACCEPT_ALL);
            cookieManager.put(uri, cookies);
            HttpCookie cookie = store.cookie;
            if (cookie != null) {
                cookieStore.add(uri, cookie);
            }
        } catch (IOException x) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Could not parse cookies", x);
            }
        }
    }

    private static class Store implements CookieStore
    {
        private HttpCookie cookie;

        @Override
        public void add(URI uri, java.net.HttpCookie cookie)
        {
            String domain = cookie.getDomain();
            if ("localhost.local".equals(domain))
                cookie.setDomain("localhost");
            this.cookie = HttpCookie.from(cookie);
        }

        @Override
        public List<java.net.HttpCookie> get(URI uri)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<java.net.HttpCookie> getCookies()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<URI> getURIs()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(URI uri, java.net.HttpCookie cookie)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll()
        {
            throw new UnsupportedOperationException();
        }
    }
}
