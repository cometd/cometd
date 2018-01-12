/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.benchmark;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicStampedReference;

public class Atomics {
    public static void updateMin(AtomicLong currentMin, long newValue) {
        long oldValue = currentMin.get();
        while (newValue < oldValue) {
            if (currentMin.compareAndSet(oldValue, newValue)) {
                break;
            }
            oldValue = currentMin.get();
        }
    }

    public static void updateMax(AtomicLong currentMax, long newValue) {
        long oldValue = currentMax.get();
        while (newValue > oldValue) {
            if (currentMax.compareAndSet(oldValue, newValue)) {
                break;
            }
            oldValue = currentMax.get();
        }
    }

    public static void updateMin(AtomicInteger currentMin, int newValue) {
        int oldValue = currentMin.get();
        while (newValue < oldValue) {
            if (currentMin.compareAndSet(oldValue, newValue)) {
                break;
            }
            oldValue = currentMin.get();
        }
    }

    public static void updateMax(AtomicInteger currentMax, int newValue) {
        int oldValue = currentMax.get();
        while (newValue > oldValue) {
            if (currentMax.compareAndSet(oldValue, newValue)) {
                break;
            }
            oldValue = currentMax.get();
        }
    }

    public static <T> int decrement(AtomicStampedReference<T> reference) {
        int oldStamp = reference.getStamp();
        while (!reference.attemptStamp(reference.getReference(), oldStamp - 1)) {
            oldStamp = reference.getStamp();
        }
        return oldStamp - 1;
    }

    public static <T> boolean updateMax(AtomicStampedReference<T> reference, T value, int stamp) {
        int oldStamp = reference.getStamp();
        while (stamp > oldStamp) {
            if (reference.compareAndSet(reference.getReference(), value, oldStamp, stamp)) {
                return true;
            }
            oldStamp = reference.getStamp();
        }
        return false;
    }
}
