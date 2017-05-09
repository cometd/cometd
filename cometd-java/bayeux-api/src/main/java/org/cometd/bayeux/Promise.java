/*
 * Copyright (c) 2008-2017 the original author or authors.
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

import java.util.concurrent.CompletableFuture;

public interface Promise<C>
{
    /**
     * <p>Callback to invoke when the operation succeeds.</p>
     *
     * @param result the result
     * @see #fail(Throwable)
     */
    default void succeed(C result)
    {
    }

    /**
     * <p>Callback to invoke when the operation fails.</p>
     *
     * @param x the operation failure
     */
    default void fail(Throwable x)
    {
    }

    static <T> Promise<T> noop() {
        return new Promise<T>() {
        };
    }

    /**
     * <p>A CompletableFuture that is also a Promise.</p>
     *
     * @param <S> the type of the result
     */
    class Completable<S> extends CompletableFuture<S> implements Promise<S>
    {
        @Override
        public void succeed(S result)
        {
            complete(result);
        }

        @Override
        public void fail(Throwable x)
        {
            completeExceptionally(x);
        }
    }
}
