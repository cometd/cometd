/*
 * Copyright (c) 2008 the original author or authors.
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

package org.cometd.server.ext;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.locks.Lock;

/**
 * <p>A concurrent queue that groups elements in batches.</p>
 * <p>The elements are stored in a growable circular array
 * along with their batch number:</p>
 * <pre>{@code
 *           head             tail
 *             |               |
 * elements: [E1, E2, E3, E4, E5]
 * batches : [ 1,  1,  2,  3,  3]
 * }</pre>
 * <p>Elements {@code E1} and {@code E2} belong to batch {@code 1},
 * only element {@code E3} belongs to batch {@code 2}, etc.</p>
 * <p>The batch can be "closed" and a new one "opened" by calling
 * {@link #nextBatch()}.</p>
 * <p>The elements can be copied with {@link #exportMessagesToBatch(Queue, long)}
 * into a different queue, and removed with {@link #clearToBatch(long)}.</p>
 *
 * @param <T> the type of elements
 */
public class BatchArrayQueue<T> implements Queue<T> {
    private final Lock lock;
    private T[] elements;
    private int head;
    private int tail;
    private long[] batches;
    private long batch;

    public BatchArrayQueue(int initial, Lock lock) {
        this.lock = lock;
        @SuppressWarnings("unchecked")
        T[] array = (T[])new Object[initial];
        this.elements = array;
        this.batches = new long[initial];
        this.batch = 1;
    }

    /**
     * <p>Adds the given element to this queue,
     * under the current batch as returned by
     * {@link #getBatch()}.</p>
     *
     * @param t the element to add
     * @return {@code true}
     */
    @Override
    public boolean offer(T t) {
        lock.lock();
        try {
            elements[tail] = Objects.requireNonNull(t);
            batches[tail] = batch;

            // Move the tail pointer, wrapping if necessary.
            ++tail;
            if (tail == elements.length) {
                tail = 0;
            }

            // If full, double capacity.
            if (tail == head) {
                int capacity = elements.length;
                int newCapacity = 2 * capacity;
                if (newCapacity < 0) {
                    throw new IllegalStateException("Could not double up capacity " + capacity);
                }

                @SuppressWarnings("unchecked")
                T[] newElements = (T[])new Object[newCapacity];
                long[] newBatches = new long[newCapacity];
                // Copy from head to end of array.
                int length = capacity - head;
                if (length > 0) {
                    System.arraycopy(elements, head, newElements, 0, length);
                    System.arraycopy(batches, head, newBatches, 0, length);
                }
                // Copy from 0 to tail if we have not done it yet.
                if (head > 0) {
                    System.arraycopy(elements, 0, newElements, length, tail);
                    System.arraycopy(batches, 0, newBatches, length, tail);
                }
                elements = newElements;
                batches = newBatches;
                head = 0;
                tail = capacity;
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    @Override
    public boolean add(T t) {
        // This queue is unbounded.
        return offer(t);
    }

    @Override
    public T peek() {
        lock.lock();
        try {
            return elements[head];
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T element() {
        T element = peek();
        if (element == null) {
            throw new NoSuchElementException();
        }
        return element;
    }

    @Override
    public T poll() {
        lock.lock();
        try {
            if (isEmpty()) {
                return null;
            }

            T result = elements[head];
            elements[head] = null;
            batches[head] = 0;

            // Move the head pointer, wrapping if necessary.
            ++head;
            if (head == elements.length) {
                head = 0;
            }

            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T remove() {
        T result = poll();
        if (result == null) {
            throw new NoSuchElementException();
        }
        return result;
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(Collection<? extends T> items) {
        lock.lock();
        try {
            boolean result = false;
            for (T item : items) {
                result |= offer(item);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> items) {
        lock.lock();
        try {
            for (Object item : items) {
                if (!contains(item)) {
                    return false;
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }

        lock.lock();
        try {
            if (isEmpty()) {
                return false;
            }

            int cursor = head;
            while (true) {
                if (o.equals(elements[cursor])) {
                    return true;
                }
                ++cursor;
                if (cursor == elements.length) {
                    cursor = 0;
                }
                if (cursor == tail) {
                    return false;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Iterator<T> iterator() {
        Object[] objects = toArray();
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < objects.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public T next() {
                return (T)objects[index++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public boolean isEmpty() {
        lock.lock();
        try {
            return head == tail;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            if (head <= tail) {
                return tail - head;
            }
            return elements.length - head + tail;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Object[] toArray() {
        return toArray(new Object[0]);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E> E[] toArray(E[] a) {
        lock.lock();
        try {
            int size = size();
            if (a.length < size) {
                a = (E[])Array.newInstance(a.getClass().getComponentType(), size);
            }
            if (head <= tail) {
                System.arraycopy(elements, head, a, 0, size);
            } else {
                int l = elements.length - head;
                System.arraycopy(elements, head, a, 0, l);
                System.arraycopy(elements, 0, a, l, tail);
            }
            return a;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Removes all the elements from this queue
     * and resets the batch number to {@code 1}.</p>
     */
    @Override
    public void clear() {
        lock.lock();
        try {
            Arrays.fill(elements, null);
            Arrays.fill(batches, 0);
            head = tail = 0;
            batch = 1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the current batch number
     */
    public long getBatch() {
        lock.lock();
        try {
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Closes the current batch and starts a new batch.</p>
     * <p>The next element offered to this queue will belong
     * to the new batch.</p>
     */
    public void nextBatch() {
        lock.lock();
        try {
            ++batch;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Removes all the elements up to the given batch number.</p>
     * <p>For example, given:</p>
     * <pre>{@code
     *           head             tail
     *             |               |
     * elements: [E1, E2, E3, E4, E5]
     * batches : [ 1,  1,  2,  3,  3]
     * }</pre>
     * <p>then calling {@code clearToBatch(1)} would leave the queue
     * in this state:</p>
     * <pre>{@code
     *                        head   tail
     *                         |       |
     * elements: [null, null, E3, E4, E5]
     * batches : [   0,    0,  2,  3,  3]
     * }</pre>
     *
     * @param batch the batch number
     * @see #exportMessagesToBatch(Queue, long)
     */
    public void clearToBatch(long batch) {
        lock.lock();
        try {
            while (true) {
                if (batches[head] > batch) {
                    break;
                }
                if (poll() == null) {
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Copies the elements of this queue into the given queue,
     * up to the given batch number.</p>
     * <p>For example, given:</p>
     * <pre>{@code
     * this queue:
     *           head             tail
     *             |               |
     * elements: [E1, E2, E3, E4, E5]
     * batches : [ 1,  1,  2,  3,  3]
     *
     * target queue: []
     * }</pre>
     * <p>then calling {@code exportMessagesToBatch(2)} would copy
     * the elements belonging to batches up to {@code 2} into the
     * target queue, leaving this queue unchanged:</p>
     * <pre>{@code
     * this queue:
     *           head             tail
     *             |               |
     * elements: [E1, E2, E3, E4, E5]
     * batches : [ 1,  1,  2,  3,  3]
     *
     * target queue: [E1, E2, E3]
     * }</pre>
     *
     * @param target the target queue
     * @param batch  the batch number
     * @see #clearToBatch(long)
     */
    public void exportMessagesToBatch(Queue<T> target, long batch) {
        lock.lock();
        try {
            int cursor = head;
            while (cursor != tail) {
                if (batches[cursor] > batch) {
                    break;
                }
                target.offer(elements[cursor]);
                ++cursor;
                if (cursor == batches.length) {
                    cursor = 0;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // Used only in tests.
    long batchOf(int index) {
        lock.lock();
        try {
            int cursor = head + index;
            int capacity = elements.length;
            if (cursor > capacity) {
                cursor -= capacity;
            }
            return batches[cursor];
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return "%s@%x[batch=%d,size=%d]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getBatch(),
                size()
        );
    }
}
