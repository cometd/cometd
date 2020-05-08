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
package org.cometd.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cometd.bayeux.Message.Mutable;
import org.cometd.common.JSONContext.NonBlockingParser;
import org.junit.Assert;
import org.junit.Test;

public class NonBlockingJacksonTest {
    JacksonJSONContextClient jacksonJSONContextClient = new JacksonJSONContextClient();
    
    @Test
    public void testParse() throws Exception {
        int objCount = 50;
        
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= objCount; i++) {
            sb.append(nextObject(i));
            if (i != objCount) {
                sb.append(',');
            }
        }
        String json = "[" +
                sb.toString()
                + "]";

        byte[] totalJson = json.getBytes();
        
        int blocks = (int) (Math.random() * 10) + 5;
        int chunkSize = totalJson.length / blocks;
        int leftover = totalJson.length % blocks;
        if (leftover > 0) {
            blocks++;
        }
        byte[][] chunks = new byte[blocks][];
        int startOffset = 0;
        for (int i = 0; i < blocks; i++) {
            if (i == blocks - 1 && leftover > 0) {
                chunkSize = leftover;
            }
            chunks[i] = Arrays.copyOfRange(totalJson, startOffset, startOffset + chunkSize);
            startOffset += chunkSize;
        }
        
        // make sure we mathed this right -- recombining the chunks
        // should get us the full json... if we fail here, it isn't the json
        // processing that's bad, we're just bad with numbers.
        StringBuilder verifySb = new StringBuilder();
        for (int i = 0; i < blocks; i++) {
            verifySb.append(new String(chunks[i]));
        }
        Assert.assertEquals(json, verifySb.toString());

        // ok finally we can actually validate the nonblocking parser stuff
        List<Mutable> ms = new ArrayList<>();
        NonBlockingParser<Mutable> nonBlockingParser = jacksonJSONContextClient.newNonBlockingParser();
        for (int i = 0; i < blocks; i++) {
            List<Mutable> m = nonBlockingParser.feed(chunks[i]);
            ms.addAll(m);
        }
        List<Mutable> m = nonBlockingParser.done();
        ms.addAll(m);
        
        Assert.assertEquals(objCount, ms.size()); // we made `objCount` objects
    }
    
    private String nextObject(int i) {
        return "{" +
                "   \"successful\":true," +
                "   \"id\":\"" + i + "\"," +
                "   \"clientId\":\"abcdefghijklmnopqrstuvwxyz\"," +
                "   \"channel\":\"/meta/connect\"," +
                "   \"data\":{" +
                "       \"peer\":\"bar\"," +
                "       \"chat\":\"woot\"," +
                "       \"user\":\"foo\"," +
                "       \"room\":\"abc\"" +
                "   }," +
                "   \"advice\":{" +
                "       \"timeout\":0" +
                "   }," +
                "   \"ext\":{" +
                "       \"com.acme.auth\":{" +
                "           \"token\":\"0123456789\"" +
                "       }" +
                "   }" +
                "}";
    }
}

