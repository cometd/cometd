/*
 * Copyright (c) 2008-2014 the original author or authors.
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

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

public class HttpCookieStore extends ScriptableObject
{
    private Map<String, Map<String, Cookie>> allCookies = new HashMap<>();

    public String getClassName()
    {
        return "HttpCookieStore";
    }

    public String jsFunction_get(String scheme, String host, String path) throws Exception
    {
        try
        {
            Map<String, Cookie> cookies = allCookies.get(host + path);
            if (cookies == null) return "";

            StringBuilder buffer = new StringBuilder();
            long now = System.currentTimeMillis();
            for (Cookie cookie : cookies.values())
            {
                if (cookie.expires != null && cookie.expires < now)
                    continue;
                if (buffer.length() > 0) buffer.append(";");
                buffer.append(cookie.name).append("=").append(cookie.value);
            }
            return buffer.toString();
        }
        catch (Exception x)
        {
            x.printStackTrace();
            throw x;
        }
    }

    public void jsFunction_set(String scheme, String host, String path, String value) throws Exception
    {
        try
        {
            Cookie cookie = new Cookie();
            String[] parts = value.split(";");
            for (String part : parts)
            {
                part = part.trim();
                if ("secure".equalsIgnoreCase(part))
                {
                    cookie.secure = true;
                }
                else
                {
                    String[] pair = part.split("=", 2);
                    String key = pair[0].trim();
                    String val = pair[1].trim();
                    if ("expires".equalsIgnoreCase(key))
                    {
                        if (cookie.expires == null)
                        {
                            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
                            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
                            cookie.expires = dateFormat.parse(val).getTime();
                        }
                    }
                    else if ("max-age".equalsIgnoreCase(key))
                    {
                        cookie.expires = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Integer.parseInt(val));
                    }
                    else if ("path".equalsIgnoreCase(key))
                    {
                        cookie.path = val;
                    }
                    else if ("domain".equalsIgnoreCase(key))
                    {
                        cookie.domain = val;
                    }
                    else if ("comment".equalsIgnoreCase(key) ||
                             "commenturl".equalsIgnoreCase(key) ||
                             "discard".equalsIgnoreCase(key) ||
                             "port".equalsIgnoreCase(key) ||
                             "version".equalsIgnoreCase(key))
                    {
                        // Less used cookie attributes, ignore for now
                    }
                    else
                    {
                        cookie.name = key;
                        cookie.value = val;
                        String cookieKey = host + path;
                        Map<String, Cookie> cookies = allCookies.get(cookieKey);
                        if (val.length() == 0)
                        {
                            if (cookies != null)
                            {
                                cookies.remove(key);
                                if (cookies.size() == 0) allCookies.remove(cookieKey);
                            }
                        }
                        else
                        {
                            if (cookies == null)
                            {
                                cookies = new HashMap<>();
                                allCookies.put(cookieKey, cookies);
                            }
                            cookies.put(key, cookie);
                        }
                    }
                }
            }
        }
        catch (Exception x)
        {
            x.printStackTrace();
            throw x;
        }
    }

    public Map<String, String> getAll(URI uri)
    {
        Map<String, String> result = new HashMap<>();
        String cookieKey = uri.getHost() + ":" + uri.getPort() + uri.getPath();
        Map<String, Cookie> cookies = allCookies.get(cookieKey);
        if (cookies != null)
        {
            long now = System.currentTimeMillis();
            for (Cookie cookie : cookies.values())
            {
                if (cookie.expires != null && cookie.expires < now)
                    continue;
                result.put(cookie.name, cookie.value);
            }
        }
        return result;
    }

    public void putAll(HttpCookieStore other)
    {
        allCookies.putAll(other.allCookies);
    }

    public void clear()
    {
        allCookies.clear();
    }

    private static class Cookie
    {
        private String name;
        private String value;
        private boolean secure;
        private String domain;
        private String path;
        private Long expires;
    }
}
