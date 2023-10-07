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
package org.cometd.bayeux;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * <p>The future result of an operation, either a value if the operation
 * succeeded, or a failure if the operation failed.</p>
 *
 * @param <C> the type of the result value
 */
public interface Promise<C> {
    /**
     * <p>Shared instance whose methods are implemented empty,</p>
     * <p>use {@link #noop()} to ease type inference.</p>
     */
    Promise<?> NOOP = new Promise<>() {};

    /**
     * <p>Callback to invoke when the operation succeeds.</p>
     *
     * @param result the result
     * @see #fail(Throwable)
     */
    default void succeed(C result) {
    }

    /**
     * <p>Callback to invoke when the operation fails.</p>
     *
     * @param failure the operation failure
     */
    default void fail(Throwable failure) {
    }

    /**
     * <p>Returns a {@code BiConsumer} that, when invoked,
     * completes this Promise.</p>
     * <p>Typical usage is with {@code CompletableFuture}:</p>
     * <pre>
     * public void process(ServerMessage message, Promise&lt;Boolean&gt; promise) {
     *     CompletableFuture.supplyAsync(() -&gt; asyncOperation(message))
     *             .whenComplete(promise.complete());
     * }
     * </pre>
     *
     * @return a BiConsumer that completes this Promise
     * @see Completable
     */
    default BiConsumer<C, Throwable> complete() {
        return (r, x) -> {
            if (x == null) {
                succeed(r);
            } else {
                fail(x);
            }
        };
    }

    /**
     * @param <T> the type of the empty result
     * @return a Promise whose methods are implemented empty.
     */
    @SuppressWarnings("unchecked")
    static <T> Promise<T> noop() {
        return (Promise<T>)NOOP;
    }

    /**
     * @param succeed the Consumer to call in case of successful completion
     * @param fail    the Consumer to call in case of failed completion
     * @param <T>     the type of the result value
     * @return a Promise from the given consumers
     */
    static <T> Promise<T> from(Consumer<T> succeed, Consumer<Throwable> fail) {
        return new Promise<>() {
            @Override
            public void succeed(T result) {
                succeed.accept(result);
            }

            @Override
            public void fail(Throwable failure) {
                fail.accept(failure);
            }
        };
    }

    /**
     * <p>Returns a Promise that, when completed,
     * invokes the given {@link BiConsumer} function.</p>
     *
     * @param fn  the function to invoke when the Promise is completed
     * @param <T> the type of the result value
     * @return a Promise that invokes the BiConsumer function
     */
    static <T> Promise<T> complete(BiConsumer<T, Throwable> fn) {
        return new Promise<>() {
            @Override
            public void succeed(T result) {
                fn.accept(result, null);
            }

            @Override
            public void fail(Throwable failure) {
                fn.accept(null, failure);
            }
        };
    }

    /**
     * <p>A CompletableFuture that is also a Promise.</p>
     */
    class Completable<S> extends CompletableFuture<S> implements Promise<S> {
        @Override
        public void succeed(S result) {
            complete(result);
        }

        @Override
        public void fail(Throwable failure) {
            completeExceptionally(failure);
        }
    }

    /**
     * <p>A wrapper for {@link Promise} instances.</p>
     *
     * @param <W> the type of the result value
     */
    class Wrapper<W> implements Promise<W> {
        private final Promise<W> wrapped;

        public Wrapper(Promise<W> wrapped) {
            this.wrapped = Objects.requireNonNull(wrapped);
        }

        public Promise<W> getWrapped() {
            return wrapped;
        }

        @Override
        public void succeed(W result) {
            getWrapped().succeed(result);
        }

        @Override
        public void fail(Throwable failure) {
            getWrapped().fail(failure);
        }
    }
}
