/*
 * Copyright (c) 2008-2021 the original author or authors.
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

/**
 * <p>A distributed counter service to be deployed on a Oort cluster that
 * modifies a {@code long} value hosted in a "primary" node.</p>
 * <p>Instances of this service may be used as unique ID generator, or as
 * unique counter across the cluster, for example to aggregate values
 * from different nodes, such as the number of users connected to each
 * node.</p>
 * <p>Applications may call methods {@link #addAndGet(long, Callback)} or
 * {@link #getAndAdd(long, Callback)} providing the amount to add
 * (it may be negative) and a {@link Callback} object that will be
 * invoked on the <em>requesting node</em> when the result has been
 * computed and transmitted back by the "primary" node.</p>
 */
public class OortPrimaryLong extends OortPrimaryService<Long, OortPrimaryLong.Context> {
    private final AtomicLong value = new AtomicLong();

    public OortPrimaryLong(Oort oort, String name, boolean primary) {
        this(oort, name, primary, 0);
    }

    /**
     * @param oort    the oort this instance is associated to
     * @param name    the name of this service
     * @param primary whether this service lives on the "primary" node
     * @param initial the initial local value
     */
    public OortPrimaryLong(Oort oort, String name, boolean primary, long initial) {
        super(oort, name, primary);
        value.set(initial);
    }

    /**
     * @return the local value, which makes sense only if called on the "primary" node.
     */
    protected long getValue() {
        return value.get();
    }

    /**
     * Retrieves the value from the "primary" node and then invokes the given {@code callback}
     * with the result value.
     *
     * @param callback the callback invoked when the result is available
     * @return whether the request could be forwarded to the "primary" node
     */
    public boolean get(Callback callback) {
        return getAndAdd(0, callback);
    }

    /**
     * Adds the given {@code delta} and then invokes the given {@code callback} with
     * the counter value after the addition.
     * The counter value may be already out of date at the moment
     * of the invocation of the {@code callback}.
     *
     * @param delta    the value to add, may be negative
     * @param callback the callback invoked when the result is available
     * @return whether the request could be forwarded to the "primary" node
     * @see #getAndAdd(long, Callback)
     */
    public boolean addAndGet(long delta, Callback callback) {
        return forward(getPrimaryOortURL(), delta, new Context(delta, callback, true));
    }

    /**
     * Adds the given {@code delta} and then invokes the given {@code callback} with
     * the counter value before the addition.
     *
     * @param delta    the value to add, may be negative
     * @param callback the callback invoked when the result is available
     * @return whether the request could be forwarded to the "primary" node
     * @see #addAndGet(long, Callback)
     */
    public boolean getAndAdd(long delta, Callback callback) {
        return forward(getPrimaryOortURL(), delta, new Context(delta, callback, false));
    }

    @Override
    protected Result<Long> onForward(Request request) {
        if (!isPrimary()) {
            return Result.ignore(0L);
        }

        long delta = ((Number)request.getData()).longValue();
        long oldValue = value.get();
        while (true) {
            if (value.compareAndSet(oldValue, oldValue + delta)) {
                break;
            }
            oldValue = value.get();
        }
        return Result.success(oldValue);
    }

    @Override
    protected void onForwardSucceeded(Long result, Context context) {
        context.callback.succeeded(context.compute ? result + context.delta : result);
    }

    @Override
    protected void onForwardFailed(Object failure, Context context) {
        context.callback.failed(failure);
    }

    /**
     * Callback invoked when the result of the operation on the counter is available,
     * or when the operation failed.
     */
    public interface Callback {
        /**
         * Callback method invoked when the operation on the counter succeeded.
         *
         * @param result the result of the operation
         */
        public default void succeeded(Long result) {
        }

        /**
         * Callback method invoked when the operation on the counter failed.
         *
         * @param failure the failure object
         */
        public default void failed(Object failure) {
        }
    }

    protected static class Context {
        private final long delta;
        private final Callback callback;
        private final boolean compute;

        private Context(long delta, Callback callback, boolean compute) {
            this.delta = delta;
            this.callback = callback;
            this.compute = compute;
        }
    }
}
