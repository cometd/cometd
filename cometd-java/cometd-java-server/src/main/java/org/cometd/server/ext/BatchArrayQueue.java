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
package org.cometd.server.ext;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;

public class BatchArrayQueue<T> implements Queue<T> {
    private final Object lock;
    private T[] elements;
    private int head;
    private int tail;
    private long[] batches;
    private long batch;

    public BatchArrayQueue(int initial, Object lock) {
        this.lock = lock;
        @SuppressWarnings("unchecked")
        T[] array = (T[])new Object[initial];
        this.elements = array;
        this.batches = new long[initial];
        this.batch = 1;
    }

    @Override
    public boolean offer(T t) {
        synchronized (lock) {
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
                    throw new IllegalStateException();
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
        synchronized (lock) {
            return elements[head];
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
        synchronized (lock) {
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
        synchronized (lock) {
            boolean result = false;
            for (T item : items) {
                result |= offer(item);
            }
            return result;
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
        synchronized (lock) {
            for (Object item : items) {
                if (!contains(item)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }

        synchronized (lock) {
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
        }
    }

    @Override
    public Iterator<T> iterator() {
        final Object[] objects = toArray();
        return new Iterator<T>() {
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
        synchronized (lock) {
            return head == tail;
        }
    }

    @Override
    public int size() {
        synchronized (lock) {
            if (head <= tail) {
                return tail - head;
            }
            return elements.length - head + tail;
        }
    }

    @Override
    public Object[] toArray() {
        return toArray(new Object[0]);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E> E[] toArray(E[] a) {
        synchronized (lock) {
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
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            Arrays.fill(elements, null);
            Arrays.fill(batches, 0);
            head = tail = 0;
            batch = 1;
        }
    }

    public long getBatch() {
        synchronized (lock) {
            return batch;
        }
    }

    public void nextBatch() {
        synchronized (lock) {
            ++batch;
        }
    }

    public void clearToBatch(long batch) {
        synchronized (lock) {
            while (true) {
                if (batches[head] > batch) {
                    break;
                }
                if (poll() == null) {
                    break;
                }
            }
        }
    }

    public void exportMessagesToBatch(Queue<T> target, long batch) {
        synchronized (lock) {
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
        }
    }

    // Used only in tests.
    long batchOf(int index) {
        synchronized (lock) {
            int cursor = head + index;
            int capacity = elements.length;
            if (cursor > capacity) {
                cursor -= capacity;
            }
            return batches[cursor];
        }
    }
}
