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
package org.cometd.common;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Promise;

public class AsyncFoldLeft {
    public static <T, R> void run(T[] array, R zero, Operation<T, R> operation, Promise<R> promise) {
        if (array.length == 0) {
            promise.succeed(zero);
        } else {
            run(Arrays.asList(array), zero, operation, promise);
        }
    }

    public static <T, R> void run(List<T> list, R zero, Operation<T, R> operation, Promise<R> promise) {
        if (list.isEmpty()) {
            promise.succeed(zero);
        } else {
            LoopImpl<T, R> loop = new LoopImpl<>(list, zero, operation, promise);
            loop.run();
        }
    }

    public interface Operation<T, R> {
        void apply(R result, T item, Loop<R> loop);
    }

    public interface Loop<R> {
        public default void proceed(R r) {
        }

        public default void leave(R r) {
        }

        public default void fail(Throwable failure) {
        }
    }

    private enum State {
        IDLE, ASYNC, PROCEED, LEAVE
    }

    private static class LoopImpl<T, R> implements Loop<R> {
        private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
        private final List<T> list;
        private int index;
        private final AtomicReference<R> result;
        private final Operation<T, R> operation;
        private final Promise<R> promise;

        public LoopImpl(List<T> list, R zero, Operation<T, R> operation, Promise<R> promise) {
            this.list = list;
            this.result = new AtomicReference<>(zero);
            this.operation = operation;
            this.promise = promise;
        }

        private void run() {
            iteration:
            while (index < list.size()) {
                state.set(State.IDLE);
                operation.apply(result.get(), list.get(index), this);

                loop:
                while (true) {
                    State current = state.get();
                    switch (current) {
                        case IDLE:
                            if (state.compareAndSet(current, State.ASYNC)) {
                                return;
                            }
                            break;
                        case PROCEED:
                            ++index;
                            break loop;
                        case LEAVE:
                            break iteration;
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
            loop:
            while (true) {
                State current = state.get();
                switch (current) {
                    case IDLE:
                        if (state.compareAndSet(current, State.PROCEED)) {
                            break loop;
                        }
                        break;
                    case ASYNC:
                        if (state.compareAndSet(current, State.PROCEED)) {
                            ++index;
                            run();
                            break loop;
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
            loop:
            while (true) {
                State current = state.get();
                switch (current) {
                    case IDLE:
                    case ASYNC:
                        if (state.compareAndSet(current, State.LEAVE)) {
                            break loop;
                        }
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
        }

        @Override
        public void fail(Throwable failure) {
            promise.fail(failure);
        }
    }

}
