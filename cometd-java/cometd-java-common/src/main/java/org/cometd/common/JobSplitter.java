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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class JobSplitter {
    /**
     * <p>Asynchronously run an action on every element of a collection.</p>
     * <p>This is equivalent to {@code CompletableFuture.runAsync(() -> elements.forEach(action), executor)} but parallelized
     * up to {@code threadCount} threads. </p>
     * @param elements the collection of elements to act upon
     * @param action the action to run on each entry of the collection
     * @param threadCount the maximum number of threads that can be used for parallelization
     * @param executor the executor to run the actions on
     * @return a CompletableFuture that completes when all the actions returned
     * @param <T> the type of the collection's elements
     */
    public static <T> CompletableFuture<Void> runAsync(Collection<T> elements, Consumer<T> action, int threadCount, Executor executor) {
        if (threadCount > 1)
            return splitWork(elements, action, threadCount, executor);
        else
            return CompletableFuture.runAsync(() -> elements.forEach(action), executor);
    }

    private static <T> CompletableFuture<Void> splitWork(Collection<T> elements, Consumer<T> action, int threads, Executor executor) {
        List<Spliterator<T>> spliteratorList = buildSpliteratorList(elements, threads);

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] completableFutures = new CompletableFuture[spliteratorList.size()];
        for (int i = 0; i < spliteratorList.size(); i++) {
            Spliterator<T> spliterator = spliteratorList.get(i);
            completableFutures[i] = CompletableFuture.runAsync(() -> spliterator.forEachRemaining(action), executor);
        }
        return CompletableFuture.allOf(completableFutures);
    }

    private static <T> List<Spliterator<T>> buildSpliteratorList(Collection<T> elements, int threads) {
        List<Spliterator<T>> spliteratorList = new ArrayList<>();
        spliteratorList.add(elements.stream().spliterator());

        while (spliteratorList.size() < threads) {
            List<Spliterator<T>> newSpliterators = new ArrayList<>();
            for (Spliterator<T> spliterator : spliteratorList) {
                Spliterator<T> newSpliterator = spliterator.trySplit();
                if (newSpliterator != null) {
                    newSpliterators.add(newSpliterator);
                    if (spliteratorList.size() + newSpliterators.size() == threads)
                        break;
                }
            }
            if (newSpliterators.isEmpty())
                break;
            spliteratorList.addAll(newSpliterators);
        }
        return spliteratorList;
    }
}
