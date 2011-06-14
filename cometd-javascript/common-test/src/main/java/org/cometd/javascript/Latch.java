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

package org.cometd.javascript;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

public class Latch extends ScriptableObject
{
    private volatile CountDownLatch latch;

    public String getClassName()
    {
        return "Latch";
    }

    public void jsConstructor(int count)
    {
        reset(count);
    }

    public void reset(int count)
    {
        latch = new CountDownLatch(count);
    }

    public boolean await(long timeout) throws InterruptedException
    {
        return latch.await(timeout, TimeUnit.MILLISECONDS);
    }

    public void jsFunction_countDown()
    {
        latch.countDown();
    }

    public long jsGet_count()
    {
        return latch.getCount();
    }
}
