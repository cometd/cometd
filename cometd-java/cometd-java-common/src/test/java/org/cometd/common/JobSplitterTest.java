/*
 * Copyright (c) 2008-2022 the original author or authors.
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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JobSplitterTest
{
    private ExecutorService executorService;

    @BeforeEach
    protected void setUp() {
        executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @AfterEach
    protected void tearDown() {
        executorService.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 10, 16, 100})
    public void testRunAsync(int count) throws Exception {
        Map<Integer, String> entries = new ConcurrentHashMap<>();
        for (int i = 0; i < count; i++) {
            entries.put(i, Integer.toString(i));
        }

        Set<String> result = ConcurrentHashMap.newKeySet();
        JobSplitter.runAsync(entries.values(), result::add, 4, executorService).get();

        assertEquals(count, result.size());
        for (int i = 0; i < count; i++) {
            assertTrue(result.contains(Integer.toString(i)));
        }
    }

    @Test
    public void testRunAsyncWorkSplitting() throws Exception {
        int count = 100_000;

        Map<Integer, String> entries = new ConcurrentHashMap<>();
        for (int i = 0; i < count; i++) {
            entries.put(i, Integer.toString(i));
        }

        Set<String> result = ConcurrentHashMap.newKeySet();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        JobSplitter.runAsync(entries.values(), e -> {
            threads.add(Thread.currentThread());
            result.add(e);
        }, 100, executorService).get();

        assertEquals(count, result.size());
        for (int i = 0; i < count; i++) {
            assertTrue(result.contains(Integer.toString(i)));
        }
        assertTrue(threads.size() >= 2);
    }
}
