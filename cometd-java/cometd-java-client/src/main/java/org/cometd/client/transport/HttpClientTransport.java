/*
 * Copyright (c) 2010 the original author or authors.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public abstract class HttpClientTransport extends ClientTransport
{
    private volatile String url;
    private volatile CookieProvider cookieProvider;

    protected HttpClientTransport(String name, Map<String, Object> options)
    {
        super(name, options);
    }

    protected String getURL()
    {
        return url;
    }

    public void setURL(String url)
    {
        this.url = url;
    }

    // TODO: remove, cookie now handled natively in HttpClient
    protected CookieProvider getCookieProvider()
    {
        return cookieProvider;
    }

    public void setCookieProvider(CookieProvider cookieProvider)
    {
        this.cookieProvider = cookieProvider;
    }

    protected Cookie getCookie(String name)
    {
        CookieProvider cookieProvider = this.cookieProvider;
        if (cookieProvider != null)
            return cookieProvider.getCookie(name);
        return null;
    }

    protected void setCookie(Cookie cookie)
    {
        CookieProvider cookieProvider = this.cookieProvider;
        if (cookieProvider != null)
            cookieProvider.setCookie(cookie);
    }

    public static class Cookie
    {
        private final String name;
        private final String value;
        private final String domain;
        private final String path;
        private final int maxAge;
        private final boolean secure;
        private final int version;
        private final String comment;
        private final long expirationTime;

        public Cookie(String name, String value, String domain, String path, int maxAge, boolean secure, int version, String comment)
        {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
            this.maxAge = maxAge;
            this.secure = secure;
            this.version = version;
            this.comment = comment;
            this.expirationTime = maxAge < 0 ? -1 : System.nanoTime() + TimeUnit.SECONDS.toNanos(maxAge);
        }

        public String getName()
        {
            return name;
        }

        public String getValue()
        {
            return value;
        }

        public String getDomain()
        {
            return domain;
        }

        public String getPath()
        {
            return path;
        }

        public int getMaxAge()
        {
            return maxAge;
        }

        public boolean isSecure()
        {
            return secure;
        }

        public int getVersion()
        {
            return version;
        }

        public String getComment()
        {
            return comment;
        }

        public boolean isExpired(long timeNanos)
        {
            return expirationTime >= 0 && timeNanos >= expirationTime;
        }

        public String asString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append(getName()).append("=").append(getValue());
            if (getPath() != null)
                builder.append(";$Path=").append(getPath());
            if (getDomain() != null)
                builder.append(";$Domain=").append(getDomain());
            return builder.toString();
        }
    }

    public interface CookieProvider
    {
        public Cookie getCookie(String name);

        public List<Cookie> getCookies();

        public void setCookie(Cookie cookie);

        public void clear();
    }

    public static class StandardCookieProvider implements CookieProvider
    {
        private final Map<String, Cookie> cookies = new ConcurrentHashMap<String, Cookie>();

        public Cookie getCookie(String name)
        {
            Cookie cookie = cookies.get(name);
            if (!cookie.isExpired(System.nanoTime()))
                return cookie;
            return null;
        }

        public void setCookie(Cookie cookie)
        {
            cookies.put(cookie.getName(), cookie);
        }

        public List<Cookie> getCookies()
        {
            List<Cookie> result = new ArrayList<Cookie>();
            for (String name : cookies.keySet())
            {
                Cookie cookie = getCookie(name);
                if (cookie != null)
                    result.add(cookie);
            }
            return result;
        }

        public void clear()
        {
            cookies.clear();
        }
    }
}
