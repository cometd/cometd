/*
 * Copyright (c) 2010 the original author or authors.
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

public class ArrayIdQueue<E> extends ArrayQueue<E>
{
    private int[] _ids;
    private int _currentId;

    public ArrayIdQueue()
    {
        this(DEFAULT_CAPACITY);
    }

    public ArrayIdQueue(int capacity)
    {
        this(capacity, -1);
    }

    public ArrayIdQueue(int initCapacity, int growBy)
    {
        this(initCapacity, growBy, null);
    }

    public ArrayIdQueue(int initCapacity, int growBy, Object lock)
    {
        super(initCapacity, growBy, lock);
        _ids = new int[initCapacity];
    }

    /**
     * @return currentId the latest batch that has been sent to the client
     */
    public int getCurrentId()
    {
        synchronized (_lock)
        {
            return _currentId;
        }
    }

    public void setCurrentId(int currentId)
    {
        synchronized (_lock)
        {
            _currentId = currentId;
        }
    }

    public void incrementCurrentId()
    {
        synchronized (_lock)
        {
            _currentId++;
        }
    }

    public boolean add(E e)
    {
        synchronized (_lock)
        {
            final int nextSlot = _nextSlot;
            boolean result = super.add(e);
            _ids[nextSlot] = _currentId;
            return result;
        }
    }

    public void addUnsafe(E e)
    {
        int nextSlot = _nextSlot;
        super.addUnsafe(e);
        _ids[nextSlot] = _currentId;
    }

    public boolean offer(E e)
    {
        synchronized (_lock)
        {
            final int nextSlot = _nextSlot;
            boolean result = super.offer(e);
            _ids[nextSlot] = _currentId;
            return result;
        }
    }

    public int getAssociatedId(int index)
    {
        synchronized (_lock)
        {
            if (index < 0 || index >= _size)
                throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");
            return getAssociatedIdUnsafe(index);
        }
    }

    public int getAssociatedIdUnsafe(int index)
    {
        int i = (_nextE + index) % _ids.length;
        return _ids[i];
    }

    public E remove(int index)
    {
        synchronized (_lock)
        {
            int nextSlot = _nextSlot;
            E e = super.remove(index);

            int i = _nextE + index;
            if (i >= _ids.length)
                i -= _ids.length;

            if (i < nextSlot)
            {
                System.arraycopy(_ids, i + 1, _ids, i, nextSlot - i);
            }
            else
            {
                System.arraycopy(_ids, i + 1, _ids, i, _ids.length - i - 1);
                if (nextSlot > 0)
                {
                    _ids[_ids.length - 1] = _ids[0];
                    System.arraycopy(_ids, 1, _ids, 0, nextSlot - 1);
                }
            }

            return e;
        }
    }

    public E set(int index, E element)
    {
        synchronized (_lock)
        {
            E old = super.set(index, element);

            int i = _nextE + index;
            if (i >= _ids.length)
                i -= _ids.length;
            // TODO: what if the id is not meant to be the latest?
            _ids[i] = _currentId;
            return old;
        }
    }

    public void add(int index, E element)
    {
        synchronized (_lock)
        {
            int nextSlot = _nextSlot;
            super.add(index, element);

            if (index == _size)
            {
                _ids[index] = _currentId;
            }
            else
            {
                int i = _nextE + index;
                if (i >= _ids.length)
                    i -= _ids.length;

                if (i < nextSlot)
                {
                    System.arraycopy(_ids, i, _ids, i + 1, nextSlot - i);
                    _ids[i] = _currentId;
                }
                else
                {
                    if (nextSlot > 0)
                    {
                        System.arraycopy(_ids, 0, _ids, 1, nextSlot);
                        _ids[0] = _ids[_ids.length - 1];
                    }

                    System.arraycopy(_ids, i, _ids, i + 1, _ids.length - i - 1);
                    _ids[i] = _currentId;
                }
            }
        }
    }

    protected boolean grow()
    {
        synchronized (_lock)
        {
            int nextE = _nextE;
            int nextSlot = _nextSlot;

            if (!super.grow())
                return false;

            int[] Ids = new int[_elements.length];
            int split = _ids.length - nextE;
            if (split > 0)
                System.arraycopy(_ids, nextE, Ids, 0, split);
            if (nextE != 0)
                System.arraycopy(_ids, 0, Ids, split, nextSlot);

            _ids = Ids;
            return true;
        }
    }
}
