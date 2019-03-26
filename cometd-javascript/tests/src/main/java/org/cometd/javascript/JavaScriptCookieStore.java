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

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * <p>Representation of the cookies in the JavaScript environment.</p>
 * <p>The actual store must survive page reloads.</p>
 */
public class JavaScriptCookieStore {
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneId.of("GMT"));
    private final CookieStore store;

    public JavaScriptCookieStore(CookieStore store) {
        this.store = store;
    }

    public CookieStore getStore() {
        return store;
    }

    public String get(String scheme, String host, String path) {
        try {
            URI uri = URI.create(scheme + "://" + host + path);
            List<HttpCookie> uriCookies = store.get(uri);
            StringBuilder buffer = new StringBuilder();
            if (uriCookies != null) {
                for (HttpCookie cookie : uriCookies) {
                    if (cookie.isHttpOnly() || cookie.hasExpired()) {
                        continue;
                    }
                    if (buffer.length() > 0) {
                        buffer.append(";");
                    }
                    buffer.append(cookie.getName()).append("=").append(cookie.getValue());
                }
            }
            return buffer.toString();
        } catch (Exception x) {
            x.printStackTrace();
            throw x;
        }
    }

    public void set(String scheme, String host, String uriPath, String cookies) throws Exception {
        try {
            String name = null;
            String value = null;
            boolean secure = false;
            boolean httpOnly = false;
            String domain = null;
            String path = null;
            Long maxAge = null;
            String[] parts = cookies.split(";");
            for (String part : parts) {
                part = part.trim();
                if ("Secure".equalsIgnoreCase(part)) {
                    secure = true;
                } else if ("HttpOnly".equalsIgnoreCase(part)) {
                    httpOnly = true;
                } else {
                    String[] pair = part.split("=", 2);
                    String key = pair[0].trim();
                    String val = pair[1].trim();
                    if ("Domain".equalsIgnoreCase(key)) {
                        domain = val;
                    } else if ("Path".equalsIgnoreCase(key)) {
                        path = val;
                    } else if ("Expires".equalsIgnoreCase(key)) {
                        if (maxAge == null) {
                            Instant parsed = formatter.parse(val, Instant::from);
                            maxAge = ChronoUnit.SECONDS.between(parsed, Instant.now());
                        }
                    } else if ("Max-Age".equalsIgnoreCase(key)) {
                        maxAge = Long.parseLong(val);
                    } else if ("Comment".equalsIgnoreCase(key) ||
                            "CommentURL".equalsIgnoreCase(key) ||
                            "Discard".equalsIgnoreCase(key) ||
                            "Port".equalsIgnoreCase(key) ||
                            "Version".equalsIgnoreCase(key)) {
                        // Less used cookie attributes, ignore for now
                    } else {
                        name = key;
                        value = val;
                    }
                }
            }

            URI uri = URI.create(scheme + "://" + host + uriPath);
            List<HttpCookie> uriCookies = store.get(uri);

            if (value == null || value.isEmpty()) {
                if (uriCookies != null) {
                    for (HttpCookie cookie : uriCookies) {
                        if (cookie.getName().equals(name)) {
                            store.remove(uri, cookie);
                            break;
                        }
                    }
                }
            } else {
                HttpCookie cookie = new HttpCookie(name, value);
                cookie.setSecure(secure);
                cookie.setHttpOnly(httpOnly);
                cookie.setDomain(domain);
                cookie.setPath(path);
                if (maxAge != null) {
                    cookie.setMaxAge(maxAge);
                }
                store.add(uri, cookie);
            }
        } catch (Exception x) {
            x.printStackTrace();
            throw x;
        }
    }
}
