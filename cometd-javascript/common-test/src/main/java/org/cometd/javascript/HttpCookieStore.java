package org.cometd.javascript;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class HttpCookieStore extends ScriptableObject
{
    private Map<String, Map<String, Cookie>> allCookies = new HashMap<String, Map<String, Cookie>>();

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
                if (cookie.expires != null && cookie.expires < now) continue;
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
                            cookie.expires = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).parse(val).getTime();
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
                                cookies = new HashMap<String, Cookie>();
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

    public void clear()
    {
        allCookies.clear();
    }

    public void putAll(HttpCookieStore other)
    {
        allCookies.putAll(other.allCookies);
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
