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

package org.cometd.server.ext;
import junit.framework.TestCase;

public class ArrayIdQueueTest extends TestCase
{

    public void testQueue() throws Exception
    {
        ArrayIdQueue<String> queue = new ArrayIdQueue<String>(3);

        int id=10;
        queue.setCurrentId(id);

        assertEquals(0,queue.size());


        for (int i=0;i<10;i++)
        {
            assertEquals(10+i,id);

            queue.offer("one");
            assertEquals(1,queue.size());

            queue.offer("two");
            assertEquals(2,queue.size());

            queue.incrementCurrentId();

            queue.offer("three");
            assertEquals(3,queue.size());

            assertEquals("one",queue.get(0));
            assertEquals(id,queue.getAssociatedId(0));
            assertEquals("two",queue.get(1));
            assertEquals(id,queue.getAssociatedId(1));
            assertEquals("three",queue.get(2));
            assertEquals(id+1,queue.getAssociatedId(2));

            assertEquals("[one, two, three]",queue.toString());

            assertEquals("two",queue.remove(1));
            assertEquals(2,queue.size());

            assertEquals("one",queue.remove());
            assertEquals(1,queue.size());

            assertEquals("three",queue.poll());
            assertEquals(0,queue.size());

            assertEquals(null,queue.poll());
            assertEquals(0,queue.size());


            queue.offer("xxx");
            queue.offer("xxx");
            assertEquals(2,queue.size());
            assertEquals("xxx",queue.poll());
            assertEquals("xxx",queue.poll());
            assertEquals(0,queue.size());

            id++;

        }

    }

}
