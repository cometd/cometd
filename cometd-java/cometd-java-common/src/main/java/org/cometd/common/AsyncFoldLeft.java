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

import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import org.cometd.bayeux.Promise;

/**
 * <p>Processes asynchronously a sequence of elements, producing a result that
 * is the reduction of the results produced by the processing of each element.</p>
 * <p>This class implements the asynchronous version of the following synchronous
 * {@code for} loop, but where the processing of each element may be asynchronous.</p>
 * <pre>{@code
 * R result;
 * for (T element : list) {
 *     (result, proceed) = operation.apply(result, element);
 *     if (!proceed) {
 *         break;
 *     }
 * }
 * }</pre>
 * <p>Using this class, the loop above becomes:</p>
 * <pre>{@code
 * R zero;
 * AsyncFoldLeft.run(list, zero, (result, element, loop) -> {
 *     CompletableFuture<R> future = processAsync(element);
 *     future.whenComplete((r, x) -> {
 *         if (x == null) {
 *             R reduced = reduce(result, r);
 *             if (shouldIterate(r)) {
 *                 loop.proceed(reduced);
 *             } else {
 *                 loop.leave(reduced);
 *             }
 *         } else {
 *             loop.fail(x);
 *         }
 *     })
 * }, Promise.complete((r, x) -> {
 *     // Process final result or failure.
 * });
 * }</pre>
 */
public class AsyncFoldLeft {
    /**
     * <p>Processes the given array of elements.</p>
     *
     * @param array     the elements to process
     * @param zero      the initial result
     * @param operation the operation to invoke for each element
     * @param promise   the promise to notify of the final result
     * @param <T>       the type of element
     * @param <R>       the type of the result
     * @see #run(Iterable, Object, Operation, Promise)
     */
    public static <T, R> void run(T[] array, R zero, Operation<T, R> operation, Promise<R> promise) {
        int size = array.length;
        if (size == 0) {
            promise.succeed(zero);
        } else {
            IndexedLoop<T, R> loop = new IndexedLoop<>(i -> array[i], size, zero, operation, promise);
            loop.run();
        }
    }

    /**
     * <p>Processes the given sequence of elements.</p>
     * <p>The initial result {@code zero} is returned if the sequence is empty.</p>
     * <p>For each element the {@link Operation#apply(Object, Object, Loop) operation}
     * function is invoked.</p>
     * <p>The sequence should have a "stable" iterator, i.e. it should not be
     * affected if the sequence is modified  during the iteration, either concurrently
     * by another thread, or by the same thread in the {@code operation} function.</p>
     *
     * @param iterable  the elements to process
     * @param zero      the initial result
     * @param operation the operation to invoke for each element
     * @param promise   the promise to notify of the final result
     * @param <T>       the type of element
     * @param <R>       the type of the result
     */
    public static <T, R> void run(Iterable<T> iterable, R zero, Operation<T, R> operation, Promise<R> promise) {
        Iterator<T> iterator = iterable.iterator();
        if (!iterator.hasNext()) {
            promise.succeed(zero);
        } else {
            IteratorLoop<T, R> loop = new IteratorLoop<>(iterator, zero, operation, promise);
            loop.run();
        }
    }

    /**
     * <p>Processes, in reverse order, the given sequence of elements.</p>
     *
     * @param list      the elements to process
     * @param zero      the initial result
     * @param operation the operation to invoke for each element
     * @param promise   the promise to notify of the final result
     * @param <T>       the type of element
     * @param <R>       the type of the result
     * @see #run(Iterable, Object, Operation, Promise)
     */
    public static <T, R> void reverseRun(List<T> list, R zero, Operation<T, R> operation, Promise<R> promise) {
        run(new ReverseIterable<>(list), zero, operation, promise);
    }

    private AsyncFoldLeft() {
    }

    /**
     * <p>The operation to invoke for each element.</p>
     *
     * @param <T> the type of element
     * @param <R> the type of the result
     */
    @FunctionalInterface
    public interface Operation<T, R> {
        /**
         * <p>Processes the given {@code element}.</p>
         * <p>The processing of the element must produce a result that is the reduction
         * of the accumulated {@code result} with the result of the processing of the
         * given {@code element}.</p>
         * <p>After the processing of the given element, the implementation must decide
         * whether to continue with or break out of the iteration (with the reduced
         * result) or to fail the iteration (with an exception).</p>
         *
         * @param result  the accumulated result so far
         * @param element the element to process
         * @param loop    the control object that continues or breaks the iteration
         */
        void apply(R result, T element, Loop<R> loop);
    }

    /**
     * <p>Controls the iteration over a sequence of elements, allowing to
     * continue the iteration (with a result), break out of the iteration
     * (with a result), or fail the iteration (with an exception).</p>
     *
     * @param <R> the type of the result
     */
    public interface Loop<R> {
        /**
         * <p>Makes the loop proceed to the next element (or the end of the loop).</p>
         *
         * @param r the result computed in the current iteration
         */
        public default void proceed(R r) {
        }

        /**
         * <p>Makes the loop exit (similarly to the {@code break} statement).</p>
         *
         * @param r the result computed in the current iteration
         */
        public default void leave(R r) {
        }

        /**
         * <p>Makes the loop fail (similarly to throwing an exception).</p>
         *
         * @param failure the failure of the current iteration
         */
        public default void fail(Throwable failure) {
        }
    }

    private enum State {
        LOOP, ASYNC, PROCEED, LEAVE, FAIL
    }

    private static abstract class AbstractLoop<T, R> implements Loop<R> {
        private final AtomicReference<State> state = new AtomicReference<>(State.LOOP);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<R> result;
        private final Operation<T, R> operation;
        private final Promise<R> promise;

        private AbstractLoop(R zero, Operation<T, R> operation, Promise<R> promise) {
            this.result = new AtomicReference<>(zero);
            this.operation = operation;
            this.promise = promise;
        }

        abstract boolean hasCurrent();

        abstract T current();

        abstract void next();

        void run() {
            while (hasCurrent()) {
                state.set(State.LOOP);
                operation.apply(result.get(), current(), this);
                loop:
                while (true) {
                    State current = state.get();
                    switch (current) {
                        case LOOP:
                            if (state.compareAndSet(current, State.ASYNC)) {
                                return;
                            }
                            break;
                        case PROCEED:
                            next();
                            break loop;
                        case LEAVE:
                            promise.succeed(result.get());
                            return;
                        case FAIL:
                            promise.fail(failure.get());
                            return;
                        default:
                            throw new IllegalStateException("Could not run loop in state " + current);
                    }
                }
            }
            promise.succeed(result.get());
        }

        @Override
        public void proceed(R r) {
            result.set(r);
            while (true) {
                State current = state.get();
                switch (current) {
                    case LOOP -> {
                        if (state.compareAndSet(current, State.PROCEED)) {
                            return;
                        }
                    }
                    case ASYNC -> {
                        if (state.compareAndSet(current, State.PROCEED)) {
                            next();
                            run();
                            return;
                        }
                    }
                    default -> throw new IllegalStateException("Could not proceed loop in state " + current);
                }
            }
        }

        @Override
        public void leave(R r) {
            result.set(r);
            while (true) {
                State current = state.get();
                switch (current) {
                    case LOOP -> {
                        if (state.compareAndSet(current, State.LEAVE)) {
                            return;
                        }
                    }
                    case ASYNC -> {
                        if (state.compareAndSet(current, State.LEAVE)) {
                            promise.succeed(result.get());
                            return;
                        }
                    }
                    default -> throw new IllegalStateException("Could not leave loop in state " + current);
                }
            }
        }

        @Override
        public void fail(Throwable x) {
            failure.compareAndSet(null, x);
            while (true) {
                State current = state.get();
                switch (current) {
                    case LOOP -> {
                        if (state.compareAndSet(current, State.FAIL)) {
                            return;
                        }
                    }
                    case ASYNC -> {
                        if (state.compareAndSet(current, State.FAIL)) {
                            promise.fail(x);
                            return;
                        }
                    }
                    default -> throw new IllegalStateException("Could not fail loop in state " + current);
                }
            }
        }
    }

    private static class IndexedLoop<T, R> extends AbstractLoop<T, R> {
        private final IntFunction<T> element;
        private final int size;
        private int index;

        private IndexedLoop(IntFunction<T> element, int size, R zero, Operation<T, R> operation, Promise<R> promise) {
            super(zero, operation, promise);
            this.element = element;
            this.size = size;
        }

        @Override
        boolean hasCurrent() {
            return index < size;
        }

        @Override
        T current() {
            return element.apply(index);
        }

        @Override
        void next() {
            ++index;
        }
    }

    private static class IteratorLoop<T, R> extends AbstractLoop<T, R> {
        private final Iterator<T> iterator;
        private boolean hasCurrent;
        private T current;

        private IteratorLoop(Iterator<T> iterator, R zero, Operation<T, R> operation, Promise<R> promise) {
            super(zero, operation, promise);
            this.iterator = iterator;
            next();
        }

        @Override
        boolean hasCurrent() {
            return hasCurrent;
        }

        @Override
        T current() {
            if (!hasCurrent) {
                throw new NoSuchElementException();
            }
            return current;
        }

        @Override
        void next() {
            hasCurrent = iterator.hasNext();
            current = hasCurrent ? iterator.next() : null;
        }
    }

    private static class ReverseIterable<T> implements Iterable<T>, Iterator<T> {
        private final ListIterator<T> iterator;

        private ReverseIterable(List<T> list) {
            this.iterator = list.listIterator(list.size());
        }

        @Override
        public Iterator<T> iterator() {
            return this;
        }

        @Override
        public boolean hasNext() {
            return iterator.hasPrevious();
        }

        @Override
        public T next() {
            return iterator.previous();
        }
    }
}
