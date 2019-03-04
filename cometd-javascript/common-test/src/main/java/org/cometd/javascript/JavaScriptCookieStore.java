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
package org.cometd.javascript;

import java.net.HttpCookie;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.HttpCookieStore;
import org.mozilla.javascript.ScriptableObject;

public class JavaScriptCookieStore extends ScriptableObject {
    private Store store;

    @Override
    public String getClassName() {
        return "JavaScriptCookieStore";
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public void clear() {
        store.clear();
    }

    public String jsFunction_get(String scheme, String host, String path) {
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

    public void jsFunction_set(String scheme, String host, String uriPath, String cookies) throws Exception {
        try {
            String name = null;
            String value = null;
            boolean secure = false;
            boolean httpOnly = false;
            String domain = null;
            String path = null;
            long maxAge = 0;
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
                        if (maxAge <= 0) {
                            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
                            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
                            maxAge = TimeUnit.MILLISECONDS.toSeconds(dateFormat.parse(val).getTime() - System.currentTimeMillis());
                        }
                    } else if ("Max-Age".equalsIgnoreCase(key)) {
                        maxAge = Integer.parseInt(val);
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
                cookie.setMaxAge(maxAge);
                store.add(uri, cookie);
            }
        } catch (Exception x) {
            x.printStackTrace();
            throw x;
        }
    }

    public static class Store extends HttpCookieStore {
        @Override
        public boolean removeAll() {
            return false;
        }

        public void clear() {
            super.removeAll();
        }
    }
}
