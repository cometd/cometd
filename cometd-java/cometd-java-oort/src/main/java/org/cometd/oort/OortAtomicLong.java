/*
 * Copyright (c) 2013 the original author or authors.
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

package org.cometd.oort;

import java.util.concurrent.atomic.AtomicLong;

public class OortAtomicLong
{
    private final AtomicLong atomic = new AtomicLong();
    private final OortObject<Long> value;

    public OortAtomicLong(Oort oort, String name)
    {
        value = new OortObject<Long>(oort, name, OortObjectFactories.forLong());
    }

    public void start()
    {
        value.start();
    }

    public void stop()
    {
        value.stop();
    }

    public long addAndGet(long delta)
    {
        long result = atomic.addAndGet(delta);
        value.setAndShare(result);
        return result;
    }

    public long getAndAdd(long delta)
    {
        long result = atomic.getAndAdd(delta);
        value.setAndShare(result);
        return result;
    }
}
