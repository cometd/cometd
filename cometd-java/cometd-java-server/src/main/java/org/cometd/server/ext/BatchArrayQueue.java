/*
 * Copyright (c) 2008-2015 the original author or authors.
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

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Queue;

import org.eclipse.jetty.util.ArrayQueue;

public class BatchArrayQueue<T> extends ArrayQueue<T>
{
    private long[] batches;
    private long batch;

    public BatchArrayQueue(int initial, Object lock)
    {
        super(initial, initial, lock);
        batches = new long[initial];
        batch = 1;
    }

    @Override
    public boolean offer(T t)
    {
        synchronized (_lock)
        {
            boolean result = super.offer(t);
            if (result)
            {
                // We need to access the old tail to assign the batch to the given element.
                int newHead = _nextE; // May have changed by super.offer() because of grow().
                int newSize = _size; // Surely changed by super.offer().
                int oldTail = (newHead + newSize - 1) % getCapacity();
                batches[oldTail] = batch;
            }
            return result;
        }
    }

    @Override
    public boolean add(T t)
    {
        // This queue is unbounded.
        return offer(t);
    }

    @Override
    public void addUnsafe(T t)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public T set(int index, T element)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int index, T element)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public T poll()
    {
        synchronized (_lock)
        {
            int head = _nextE;
            T result = super.poll();
            if (result != null)
                batches[head] = 0;
            return result;
        }
    }

    @Override
    public T remove()
    {
        T result = poll();
        if (result == null)
            throw new NoSuchElementException();
        return result;
    }

    @Override
    public void clear()
    {
        synchronized (_lock)
        {
            super.clear();
            Arrays.fill(batches, 0);
            batch = 1;
        }
    }

    @Override
    public T remove(int index)
    {
        throw new UnsupportedOperationException();
    }

    public long getBatch()
    {
        synchronized (_lock)
        {
            return batch;
        }
    }

    public void nextBatch()
    {
        synchronized (_lock)
        {
            ++batch;
        }
    }

    public void clearToBatch(long batch)
    {
        synchronized (_lock)
        {
            while (true)
            {
                int head = _nextE;
                if (batches[head] > batch)
                    break;
                if (poll() == null)
                    break;
            }
        }
    }

    public void exportMessagesToBatch(Queue<T> target, long batch)
    {
        synchronized (_lock)
        {
            if (isEmpty())
                return;
            int index = 0;
            while (index < _size)
            {
                int cursor = (_nextE + index) % getCapacity();
                if (batches[cursor] > batch)
                    break;
                T element = getUnsafe(index);
                target.offer(element);
                ++index;
            }
        }
    }

    public int batchSize(long value)
    {
        synchronized (_lock)
        {
            int result = 0;
            int index = 0;
            while (index < _size)
            {
                int cursor = (_nextE + index) % getCapacity();
                long batch = batches[cursor];
                if (batch == value)
                    ++result;
                else if (batch > value)
                    break;
                ++index;
            }
            return result;
        }
    }

    @Override
    protected boolean grow()
    {
        synchronized (_lock)
        {
            int head = _nextE;
            int tail = _nextSlot;

            if (!super.grow())
                return false;

            long[] newIds = new long[_elements.length];
            int length = batches.length - head;
            // Copy from head to end of array.
            if (length > 0)
                System.arraycopy(batches, head, newIds, 0, length);
            // Copy from 0 to tail if we have not done it yet.
            if (head != 0)
                System.arraycopy(batches, 0, newIds, length, tail);
            batches = newIds;
            return true;
        }
    }

    // Used only in tests.
    long batchOf(int index)
    {
        synchronized (_lock)
        {
            int cursor = (_nextE + index) % getCapacity();
            return batches[cursor];
        }
    }
}
