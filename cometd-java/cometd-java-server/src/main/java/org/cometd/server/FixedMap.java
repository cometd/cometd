package org.cometd.server;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;


/* ------------------------------------------------------------ */
/** Fixed Map.
 * <p>FixedMap is a hash {@link Map} implementation that uses a 
 * fixed size array of hash ebtries whose keys and hashes are retained
 * when removed from the map.   FixedMaps are optimized for pooled maps
 * that will frequently contain the same key over and over.
 * </p>
 * <p>FixedMap keys cannot be null.   FixedMap values may be null, but 
 * null values are treated exactly as if the entry is not added to the
 * map.  Setting a value to null is equivalent to removing the entry
 * </p>
 * <p>The {@link #getEntry(Object))} may be used to obtain references
 * to Map.Entry instances that will not change for a given key and thus
 * may be used for direct access to the related value.
 * </p>
 * 
 * @param <K> The key type
 * @param <V> The key value
 */
public class FixedMap<K,V> extends AbstractMap<K, V> implements Map<K,V>
{
    final Entry<K,V>[] _entries;
    final EntrySet _entrySet;
    int _size;

    FixedMap()
    {
        this(8);
    }
    
    FixedMap(int nominalSize)
    {
        int capacity = 1;
        while (capacity < nominalSize) 
            capacity <<= 1;
        _entries=new Entry[capacity];
        _entrySet = new EntrySet();
    }
    
    /* ------------------------------------------------------------ */
    /** Called if the map is about to be changed.
     * @param key The key to be changed, or null if multiple keys.
     * @throws UnsupportedOperationException If change is not allowed/
     */
    protected void change(K key)
        throws UnsupportedOperationException 
    {
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet()
    {
        return _entrySet;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public V put(K key, V value) 
    {
        if (key == null)
            throw new IllegalArgumentException();
        
        change(key);
        
        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);
        
        Entry<K,V> last = null;
        for (Entry<K,V> e = _entries[index]; e != null; e = e._next) 
        {
            if (e._hash == hash && key.equals(e._key)) 
            {
                V old=e.setValue(value);
                return old;
            }
            last=e;
        }

        Entry<K,V> e = new Entry<K,V>(this,hash,key,value);
        if (last==null)
            _entries[index]=e;
        else
            last._next=e;
        return null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean containsKey(Object key)
    {
        if (key == null)
            throw new IllegalArgumentException();
        
        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);
        
        for (Entry<K,V> e = _entries[index]; e != null; e = e._next) 
        {
            if (e._hash == hash && key.equals(e._key)) 
                return true;
        }

        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public V get(Object key)
    {
        if (key == null)
            throw new IllegalArgumentException();
        
        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);
        
        for (Entry<K,V> e = _entries[index]; e != null; e = e._next) 
        {
            if (e._hash == hash && key.equals(e._key)) 
                return e._value;
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    public Map.Entry<K,V> getEntry(K key)
    {
        if (key == null)
            throw new IllegalArgumentException();
        
        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);
        
        for (Entry<K,V> e = _entries[index]; e != null; e = e._next) 
        {
            if (e._hash == hash && key.equals(e._key)) 
                return e;
        }
        return null;
    }    

    /* ------------------------------------------------------------ */
    @Override
    public void clear()
    {
        change(null);
        
        for (int i=_entries.length; i-->0;)
        {
            int depth=0;

            for (Entry<K,V> e = _entries[i]; e != null; e = e._next)
            {
                e.setValue(null);
                if (++depth>_entries.length)
                {
                    e._next=null;
                    break;
                }
            }
        }
        _size=0;
    }

    /* ------------------------------------------------------------ */
    @Override
    public V remove(Object key)
    {
        if (key == null)
            throw new IllegalArgumentException();

        change((K)key);
        
        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);
        
        for (Entry<K,V> e = _entries[index]; e != null; e = e._next) 
        {
            if (e._hash == hash && key.equals(e._key)) 
            {
                V old=e.setValue(null);
                return old;
            }
        }

        return null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int size()
    {
        return _size;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class EntrySet extends AbstractSet<java.util.Map.Entry<K, V>>
    {
        @Override
        public Iterator<java.util.Map.Entry<K, V>> iterator()
        {
            return new EntryIterator();
        }

        @Override
        public int size()
        {
            return _size;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    static class Entry<K,V> implements Map.Entry<K,V> 
    {
        final FixedMap _map;
        final K _key;
        final int _hash;
        V _value;
        Entry<K,V> _next;

        Entry(FixedMap map,int hash, K k, V v) 
        {
            _map=map;
            _value = v;
            if (_value!=null)
                _map._size++;
            _key = k;
            _hash = hash;
        }

        public K getKey() 
        {
            return _key;
        }

        public V getValue() 
        {
            return _value;
        }
    
        public V setValue(V newValue) 
        {
            _map.change(_key);
            
            V old = _value;
            _value = newValue;
            
            if (old!=null && _value==null)
                _map._size--;
            else if (old==null && _value!=null)
                _map._size++;
            
            return old;
        }
    
        public boolean equals(Object o) 
        {
            if (!(o instanceof Map.Entry<?,?>))
                return false;
            Map.Entry<?,?> e = (Map.Entry<?,?>)o;
            Object k1 = getKey();
            Object k2 = e.getKey();
            if (k1 == k2 || (k1 != null && k1.equals(k2))) {
                Object v1 = getValue();
                Object v2 = e.getValue();
                if (v1 == v2 || (v1 != null && v1.equals(v2))) 
                    return true;
            }
            return false;
        }
    
        public int hashCode() 
        {
            return _hash;
        }
    
        public String toString() 
        {
            return _key + "=" + _value;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class EntryIterator implements Iterator<java.util.Map.Entry<K,V>>
    {
        int _index=0;
        Entry<K,V> _entry;
        Entry<K,V> _last;
        
        EntryIterator()
        {
            while(_entry==null && _index<_entries.length)
            {
                _entry=_entries[_index++];
                while(_entry!=null && _entry._value==null)
                {
                    _entry=_entry._next;
                }
            }
        }
        
        public boolean hasNext()
        {
            return _entry!=null;
        }

        public Entry<K, V> next()
        {
            if (_entry==null)
                throw new NoSuchElementException();
            Entry<K,V> entry=_entry;
            
            _entry=_entry._next;
            while(_entry!=null && _entry._value==null)
            {
                _entry=_entry._next;
            }
            while(_entry==null && _index<_entries.length)
            {
                _entry=_entries[_index++];
                while(_entry!=null && _entry._value==null)
                {
                    _entry=_entry._next;
                }
            }
            _last=entry;
            return entry;
        }

        public void remove()
        {
            if (_last==null)
                throw new NoSuchElementException();
            _last.setValue(null);
        }  
    }
}
