/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Promise;

/**
 * <p>Processes asynchronously a sequence of elements, producing a result that
 * is the reduction of the results produced by the processing of each element.</p>
 * <p>This class implements the asynchronous version of the following synchronous
 * {@code for} loop, but where the processing of each element may be asynchronous.</p>
 * <pre>
 * R result;
 * for (T element : list) {
 *     (result, proceed) = operation.apply(result, element);
 *     if (!proceed) {
 *         break;
 *     }
 * }
 * </pre>
 * <p>Using this class, the loop above becomes:</p>
 * <pre>
 * R zero;
 * AsyncFoldLeft.run(list, zero, (result, element, loop) -&gt; {
 *     CompletableFuture&lt;R&gt; future = processAsync(element);
 *     future.whenComplete((r, x) -&gt; {
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
 * }, Promise.complete((r, x) -&gt; {
 *     // Process final result or failure.
 * });
 * </pre>
 */
public class AsyncFoldLeft {
    public static <T, R> void run(T[] array, R zero, Operation<T, R> operation, Promise<R> promise) {
        if (array.length == 0) {
            promise.succeed(zero);
        } else {
            run(Arrays.asList(array), zero, operation, promise);
        }
    }

    /**
     * <p>Processes the given {@code list} of elements.</p>
     * <p>The initial result {@code zero} is returned if the list is empty.</p>
     * <p>For each element the {@link Operation#apply(Object, Object, Loop) operation}
     * function is invoked.</p>
     *
     * @param list      the elements to process
     * @param zero      the initial result
     * @param operation the operation to invoke for each element
     * @param promise   the promise to notify of the final result
     * @param <T>       the type of element
     * @param <R>       the type of the result
     */
    public static <T, R> void run(List<T> list, R zero, Operation<T, R> operation, Promise<R> promise) {
        if (list.isEmpty()) {
            promise.succeed(zero);
        } else {
            LoopImpl<T, R> loop = new LoopImpl<>(list, zero, operation, promise);
            loop.run();
        }
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
        public default void proceed(R r) {
        }

        public default void leave(R r) {
        }

        public default void fail(Throwable failure) {
        }
    }

    private enum State {
        LOOP, ASYNC, PROCEED, LEAVE, FAIL
    }

    private static class LoopImpl<T, R> implements Loop<R> {
        private final AtomicReference<State> state = new AtomicReference<>(State.LOOP);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<T> list;
        private final AtomicReference<R> result;
        private final Operation<T, R> operation;
        private final Promise<R> promise;
        private int index;

        public LoopImpl(List<T> list, R zero, Operation<T, R> operation, Promise<R> promise) {
            this.list = list;
            this.result = new AtomicReference<>(zero);
            this.operation = operation;
            this.promise = promise;
        }

        private void run() {
            while (index < list.size()) {
                state.set(State.LOOP);
                operation.apply(result.get(), list.get(index), this);
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
                            ++index;
                            break loop;
                        case LEAVE:
                            promise.succeed(result.get());
                            return;
                        case FAIL:
                            promise.fail(failure.get());
                            return;
                        default:
                            throw new IllegalStateException();
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
                    case LOOP:
                        if (state.compareAndSet(current, State.PROCEED)) {
                            return;
                        }
                        break;
                    case ASYNC:
                        if (state.compareAndSet(current, State.PROCEED)) {
                            ++index;
                            run();
                            return;
                        }
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
        }

        @Override
        public void leave(R r) {
            result.set(r);
            while (true) {
                State current = state.get();
                switch (current) {
                    case LOOP:
                    case ASYNC:
                        if (state.compareAndSet(current, State.LEAVE)) {
                            return;
                        }
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
        }

        @Override
        public void fail(Throwable x) {
            failure.compareAndSet(null, x);
            while (true) {
                State current = state.get();
                switch (current) {
                    case LOOP:
                        if (state.compareAndSet(current, State.FAIL)) {
                            return;
                        }
                        break;
                    case ASYNC:
                        if (state.compareAndSet(current, State.FAIL)) {
                            promise.fail(x);
                            return;
                        }
                    default:
                        throw new IllegalStateException();
                }
            }
        }
    }
}
