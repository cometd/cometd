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
package org.cometd.bayeux;

import java.util.concurrent.atomic.AtomicMarkableReference;

/**
 * Immutable, non-volatile, non-atomic version of {@link AtomicMarkableReference}.
 *
 * @param <T> the type of the reference
 */
public class MarkedReference<T> {
    private static final MarkedReference<?> EMPTY = new MarkedReference<>(null, false);

    /**
     * @return a null-reference, non-marked instance
     * @param <S> the type of the null reference
     */
    public static <S> MarkedReference<S> empty() {
        return (MarkedReference<S>)EMPTY;
    }

    private final T reference;
    private final boolean marked;

    public MarkedReference(T reference, boolean marked) {
        this.reference = reference;
        this.marked = marked;
    }

    public T getReference() {
        return reference;
    }

    public boolean isMarked() {
        return marked;
    }
}
