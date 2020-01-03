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
package org.cometd.javascript;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class Latch {
    private final AtomicReference<CountDownLatch> latch = new AtomicReference<>();

    public Latch(int count) {
        reset(count);
    }

    public void reset(int count) {
        latch.set(new CountDownLatch(count));
    }

    public boolean await(long timeout) throws InterruptedException {
        return latch.get().await(timeout, TimeUnit.MILLISECONDS);
    }

    public void countDown() {
        latch.get().countDown();
    }

    public long getCount() {
        return latch.get().getCount();
    }
}
