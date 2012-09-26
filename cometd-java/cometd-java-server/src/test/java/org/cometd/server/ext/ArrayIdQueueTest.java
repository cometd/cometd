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

import org.junit.Assert;
import org.junit.Test;

public class ArrayIdQueueTest
{
    @Test
    public void testQueue() throws Exception
    {
        ArrayIdQueue<String> queue = new ArrayIdQueue<>(3);

        int id=10;
        queue.setCurrentId(id);

        Assert.assertEquals(0, queue.size());

        for (int i=0;i<10;i++)
        {
            Assert.assertEquals(10 + i, id);

            queue.offer("one");
            Assert.assertEquals(1, queue.size());

            queue.offer("two");
            Assert.assertEquals(2, queue.size());

            queue.incrementCurrentId();

            queue.offer("three");
            Assert.assertEquals(3, queue.size());

            Assert.assertEquals("one", queue.get(0));
            Assert.assertEquals(id, queue.getAssociatedId(0));
            Assert.assertEquals("two", queue.get(1));
            Assert.assertEquals(id, queue.getAssociatedId(1));
            Assert.assertEquals("three", queue.get(2));
            Assert.assertEquals(id + 1, queue.getAssociatedId(2));

            Assert.assertEquals("[one, two, three]", queue.toString());

            Assert.assertEquals("two", queue.remove(1));
            Assert.assertEquals(2, queue.size());

            Assert.assertEquals("one", queue.remove());
            Assert.assertEquals(1, queue.size());

            Assert.assertEquals("three", queue.poll());
            Assert.assertEquals(0, queue.size());

            Assert.assertEquals(null, queue.poll());
            Assert.assertEquals(0, queue.size());


            queue.offer("xxx");
            queue.offer("xxx");
            Assert.assertEquals(2, queue.size());
            Assert.assertEquals("xxx", queue.poll());
            Assert.assertEquals("xxx", queue.poll());
            Assert.assertEquals(0, queue.size());

            id++;
        }
    }
}
