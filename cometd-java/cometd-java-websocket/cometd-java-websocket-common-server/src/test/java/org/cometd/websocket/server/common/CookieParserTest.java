/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.websocket.server.common;

import java.net.HttpCookie;
import java.text.ParseException;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class CookieParserTest {
    @Test(expected = ParseException.class)
    public void testNoName() throws Exception {
        String header = "=value";
        CookieParser.parse(header);
    }

    @Test(expected = ParseException.class)
    public void parseUnclosedQuote() throws Exception {
        String name = "name";
        String value = "value";
        String header = name + "=\"" + value;
        CookieParser.parse(header);
    }

    @Test
    public void parseCookie() throws Exception {
        String name = "name";
        String value = "value";
        String header = name + "=" + value;
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        Assert.assertEquals(name, cookie.getName());
        Assert.assertEquals(value, cookie.getValue());
    }

    @Test
    public void parseNameStartingWithWhiteSpace() throws Exception {
        String name = "name";
        String value = "value";
        String header = " " + name + "=" + value;
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        Assert.assertEquals(name, cookie.getName());
        Assert.assertEquals(value, cookie.getValue());
    }

    @Test
    public void parseNameEndingWithWhiteSpace() throws Exception {
        String name = "name";
        String value = "value";
        String header = name + " =" + value;
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        Assert.assertEquals(name, cookie.getName());
        Assert.assertEquals(value, cookie.getValue());
    }

    @Test
    public void parseValueStartingWithWhiteSpace() throws Exception {
        String name = "name";
        String value = "value";
        String header = name + "= " + value;
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        Assert.assertEquals(name, cookie.getName());
        Assert.assertEquals(value, cookie.getValue());
    }

    @Test
    public void parseValueEndingWithWhiteSpace() throws Exception {
        String name = "name";
        String value = "value";
        String header = name + "=" + value + " ";
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie = cookies.get(0);
        Assert.assertEquals(name, cookie.getName());
        Assert.assertEquals(value, cookie.getValue());
    }

    @Test
    public void parseTwoCookies() throws Exception {
        String name1 = "name1";
        String value1 = "value1";
        String name2 = "name2";
        String value2 = "value2";
        String header = name1 + "=" + value1 + "; " + name2 + " = " + value2;
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(2, cookies.size());
        HttpCookie cookie1 = cookies.get(0);
        Assert.assertEquals(name1, cookie1.getName());
        Assert.assertEquals(value1, cookie1.getValue());
        HttpCookie cookie2 = cookies.get(1);
        Assert.assertEquals(name2, cookie2.getName());
        Assert.assertEquals(value2, cookie2.getValue());
    }

    @Test
    public void parseTwoCookiesFirstQuoted() throws Exception {
        String name1 = "name1";
        String value1 = "value1";
        String name2 = "name2";
        String value2 = "value2";
        String header = name1 + "=\"" + value1 + "\"; " + name2 + " = " + value2 + "; ";
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(2, cookies.size());
        HttpCookie cookie1 = cookies.get(0);
        Assert.assertEquals(name1, cookie1.getName());
        Assert.assertEquals(value1, cookie1.getValue());
        HttpCookie cookie2 = cookies.get(1);
        Assert.assertEquals(name2, cookie2.getName());
        Assert.assertEquals(value2, cookie2.getValue());
    }

    @Test
    public void parseTwoCookiesSecondQuoted() throws Exception {
        String name1 = "name1";
        String value1 = "value1";
        String name2 = "name2";
        String value2 = "value2";
        String header = name1 + "=" + value1 + "; " + name2 + " = \"" + value2 + "\" ; ";
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(2, cookies.size());
        HttpCookie cookie1 = cookies.get(0);
        Assert.assertEquals(name1, cookie1.getName());
        Assert.assertEquals(value1, cookie1.getValue());
        HttpCookie cookie2 = cookies.get(1);
        Assert.assertEquals(name2, cookie2.getName());
        Assert.assertEquals(value2, cookie2.getValue());
    }

    @Test
    public void parseCookieQuotedWithSemicolonInValue() throws Exception {
        String name = "name";
        String value = "va;lue";
        String header = name + "=\"" + value + "\"";
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie1 = cookies.get(0);
        Assert.assertEquals(name, cookie1.getName());
        Assert.assertEquals(value, cookie1.getValue());
    }

    @Test
    public void parseCookieWithNameValueAttribute() throws Exception {
        String name = "name";
        String value = "value";
        String header = name + "=" + value + "; $Path=/";
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie1 = cookies.get(0);
        Assert.assertEquals(name, cookie1.getName());
        Assert.assertEquals(value, cookie1.getValue());
    }

    @Test
    public void parseCookieWithVersionAttributeAtBeginning() throws Exception {
        String name = "name";
        String value = "value";
        String header = "$Version=1; " + name + "=" + value;
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie1 = cookies.get(0);
        Assert.assertEquals(name, cookie1.getName());
        Assert.assertEquals(value, cookie1.getValue());
    }

    @Test
    public void parseCookieWithNameOnlyAttribute() throws Exception {
        String name = "name";
        String value = "value";
        String header = name + "=" + value + "; $Port";
        List<HttpCookie> cookies = CookieParser.parse(header);
        Assert.assertEquals(1, cookies.size());
        HttpCookie cookie1 = cookies.get(0);
        Assert.assertEquals(name, cookie1.getName());
        Assert.assertEquals(value, cookie1.getValue());
    }
}
