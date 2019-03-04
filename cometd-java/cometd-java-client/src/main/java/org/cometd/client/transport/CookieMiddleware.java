/*
 * Copyright (c) 2008-2017 the original author or authors.
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CookieMiddleware {

    Collection<HttpCookie> extractCookies(String url);
    void storeCookies(String url, Map<String, List<String>> cookies);

    class DefaultCookieMiddleware implements CookieMiddleware {

        protected final Logger logger = LoggerFactory.getLogger(getClass());


        private final CookieManager _cookieManager;

        private final CookieStore _cookieStore;

        public DefaultCookieMiddleware(CookieManager cookieManager, CookieStore cookieStore) {
            _cookieManager = cookieManager;
            _cookieStore = cookieStore;
        }

        @Override
        public void storeCookies(String url, Map<String, List<String>> cookies) {
            final URI uri = URI.create(url);
            try {
                _cookieManager.put(uri, cookies);
            } catch (IOException x) {
                if (logger.isDebugEnabled()) {
                    logger.debug("", x);
                }
            }
        }

        public Collection<HttpCookie> extractCookies(String url) {
            final URI uri = URI.create(url);
            return _cookieStore.get(uri);
        }
    }
}

