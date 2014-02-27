/*
 * Copyright (c) 2008-2014 the original author or authors.
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

import org.eclipse.jetty.util.ArrayQueue;

public class BatchArrayQueue<T> extends ArrayQueue<T>
{
    private long[] batchIds;
    private long batch;

    public BatchArrayQueue(int initial, int growBy, Object lock)
    {
        super(initial, growBy, lock);
        batchIds = new long[initial];
        batch = 1;
    }

    @Override
    public boolean offer(T t)
    {
        synchronized (_lock)
        {
            int tail = _nextSlot;
            boolean result = super.offer(t);
            if (result)
                batchIds[tail] = batch;
            return result;
        }
    }

    @Override
    public boolean add(T t)
    {
        throw new UnsupportedOperationException();
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
            batchIds[head] = 0;
            return result;
        }
    }

    @Override
    public T remove()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear()
    {
        throw new UnsupportedOperationException();
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

    public long getAndIncrementBatch()
    {
        synchronized (_lock)
        {
            long result = batch;
            ++batch;
            return result;
        }
    }

    public void clearToBatch(long batch)
    {
        synchronized (_lock)
        {
            while (true)
            {
                int head = _nextE;
                if (batchIds[head] > batch)
                    break;
                if (poll() == null)
                    break;
            }
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
            int length = batchIds.length - head;
            // Copy from head to end of array.
            if (length > 0)
                System.arraycopy(batchIds, head, newIds, 0, length);
            // Copy from 0 to tail if we have not done it yet.
            if (head != 0)
                System.arraycopy(batchIds, 0, newIds, length, tail);
            batchIds = newIds;
            return true;
        }
    }
}
