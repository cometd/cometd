/*
 * Copyright (c) 2011 the original author or authors.
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

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.junit.Test;

public class StringQuoterTest
{
    @Test
    public void testQuoting()
    {
        String string = "com.acme.auth";
        int count = 5;
        int iterations = 12000000;

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < count; ++i)
        {
            long start = System.nanoTime();
            for (int j = 0; j < iterations; ++j)
            {
                buffer.setLength(0);
                QuotedStringTokenizer.quote(buffer, string);
            }
            long end = System.nanoTime();
            System.err.printf("QST iteration %d: %d ms%n", i, TimeUnit.NANOSECONDS.toMillis(end - start));
        }

        for (int i = 0; i < count; ++i)
        {
            long start = System.nanoTime();
            for (int j = 0; j < iterations; ++j)
            {
                buffer.setLength(0);
                StringQuoter.quote(buffer, string);
            }
            long end = System.nanoTime();
            System.err.printf("SQ iteration %d: %d ms%n", i, TimeUnit.NANOSECONDS.toMillis(end - start));
        }
    }

    private static class StringQuoter
    {
        private static final char[] escapes = new char[31];
        static
        {
            Arrays.fill(escapes, (char)-1);
            escapes['\b'] = 'b';
            escapes['\t'] = 't';
            escapes['\n'] = 'n';
            escapes['\f'] = 'f';
            escapes['\r'] = 'r';
        }

        public static void quote(Appendable buffer, String input)
        {
            try
            {
                buffer.append('"');
                for (int i = 0; i < input.length(); ++i)
                {
                    char c = input.charAt(i);
                    if (c >= 32)
                    {
                        if (c == '"' || c == '\\')
                            buffer.append('\\');
                        buffer.append(c);
                    }
                    else
                    {
                        char escape = escapes[c];
                        if (escape == -1)
                        {
                            // Unicode escape
                            buffer.append('\\').append('0').append('0');
                            if (c < 0x10)
                                buffer.append('0');
                            buffer.append(Integer.toString(c, 16));
                        }
                        else
                        {
                            buffer.append('\\').append(escape);
                        }
                    }
                }
                buffer.append('"');
            }
            catch (IOException x)
            {
                throw new RuntimeException(x);
            }
        }
    }
}
