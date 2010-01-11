package org.cometd.util;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;


/* ------------------------------------------------------------ */
/** Immutable Hash Map.
 * <p>FixedHashMap is a hash {@link Map} implementation that provides both
 * mutable and immutable APIs to the same data structure.  The immutable
 * API applies to deep structures of FixedHashMaps of FixedHashMaps.
 * </p>
 * <p>The implementation uses a fixed size array of hash entries whose keys 
 * and hashes are retained when removed from the map, which is  optimal 
 * for pooled maps that will frequently contain the same key over and over.
 * </p>
 * <p>FixedMap keys cannot be null.   FixedMap values may be null, but 
 * null values are treated exactly as if the entry is not added to the
 * map.  Setting a value to null is equivalent to removing the entry
 * </p>
 * <p>The {@link #getEntry(Object))} may be used to obtain references
 * to Map.Entry instances that will not change for a given key and thus
 * may be used for direct access to the related value.
 * </p>
 * <p>This map is not thread safe and multiple threads should not access
 * the map without some synchronization</p>. 
 * 
 * @param <K> The key type
 * @param <V> The key value
 */
public class ImmutableHashMap<K,V> extends AbstractMap<K, V> implements Map<K,V>
{
    private final DualEntry[] _entries;
    private final Mutable _mutable;
    private final ImmutableEntrySet _immutableSet;
    private final MutableEntrySet _mutableSet;
    private int _size;

    /* ------------------------------------------------------------ */
    public ImmutableHashMap()
    {
        this(16);
    }

    /* ------------------------------------------------------------ */
    public ImmutableHashMap(int nominalSize)
    {
        int capacity = 1;
        while (capacity < nominalSize) 
            capacity <<= 1;
        _entries=new DualEntry[capacity];
        _mutable=new Mutable();
        _immutableSet = new ImmutableEntrySet();
        _mutableSet = new MutableEntrySet();
    }

    /* ------------------------------------------------------------ */
    /** Get the immutable API to this map.
     * @return an Immutable map backed by this map.
     */
    public Mutable asMutable()
    {
        return _mutable;
    }

    /* ------------------------------------------------------------ */
    /** Get an entry reference.
     * The first [nominalSize] entries added are guaranteed never to
     * be deleted from the map, so the references may be used as repeated
     * quick lookups of the same key.
     * @param key
     * @return
     */
    public Map.Entry<K,V> getEntry(K key)
    {
        if (key == null)
            throw new IllegalArgumentException();
        
        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);
        
        for (DualEntry<K,V> e = _entries[index]; e != null; e = e._next) 
        {
            if (e._hash == hash && key.equals(e._key)) 
                return e;
        }
        return null;
    }    
    
    /* ------------------------------------------------------------ */
    /** Called if the map is about to be changed.
     * @param key The key to be changed, or null if multiple keys.
     * @throws UnsupportedOperationException If change is not allowed/
     */
    protected void onChange(K key)
        throws UnsupportedOperationException 
    {
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet()
    {
        return _immutableSet;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean containsKey(Object key)
    {
        return _mutable.containsKey(key);
    }

    /* ------------------------------------------------------------ */
    @Override
    public V get(Object key)
    {
        if (key == null)
            throw new IllegalArgumentException();

        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);

        for (DualEntry<K,V> e = _entries[index]; e != null; e = e._next) 
        {
            if (e._hash == hash && key.equals(e._key)) 
                return e.getValue();
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
    public class Mutable extends AbstractMap<K, V> implements Map<K,V>
    {
        public ImmutableHashMap<K,V> asImmutable()
        {
            return ImmutableHashMap.this;
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public Set<java.util.Map.Entry<K, V>> entrySet()
        {
            return _mutableSet;
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean containsKey(Object key)
        {
            if (key == null)
                throw new IllegalArgumentException();

            final int hash = key.hashCode();
            final int index=hash & (_entries.length-1);

            for (DualEntry<K,V> e = _entries[index]; e != null; e = e._next) 
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

            for (DualEntry<K,V> e = _entries[index]; e != null; e = e._next) 
            {
                if (e._hash == hash && key.equals(e._key)) 
                    return e._mutable.getValue();
            }
            return null;
        }

        /* ------------------------------------------------------------ */
        /** Get an entry reference.
         * The first [nominalSize] entries added are guarenteed never to
         * be deleted from the map, so the references may be used as repeated
         * quick lookups of the same key.
         * @param key
         * @return
         */
        public Map.Entry<K,V> getEntry(K key)
        {
            if (key == null)
                throw new IllegalArgumentException();
            
            final int hash = key.hashCode();
            final int index=hash & (_entries.length-1);
            
            for (DualEntry<K,V> e = _entries[index]; e != null; e = e._next) 
            {
                if (e._hash == hash && key.equals(e._key)) 
                    return e._mutable;
            }
            return null;
        }    

        /* ------------------------------------------------------------ */
        @Override
        public V put(K key, V value) 
        {
            if (key == null)
                throw new IllegalArgumentException();
            
            onChange(key);
            
            final int hash = key.hashCode();
            final int index=hash & (_entries.length-1);
            
            DualEntry<K,V> last = null;
            for (DualEntry<K,V> e = _entries[index]; e != null; e = e._next) 
            {
                if (e._hash == hash && key.equals(e._key)) 
                {
                    V old=e._mutable.setValue(value);
                    return old;
                }
                last=e;
            }

            DualEntry<K,V> e = new DualEntry<K,V>(ImmutableHashMap.this,hash,key,value);
            if (last==null)
                _entries[index]=e;
            else
                last._next=e;
            return null;
        }

        /* ------------------------------------------------------------ */
        @Override
        public void clear()
        {
            onChange(null);
            
            for (int i=_entries.length; i-->0;)
            {
                int depth=0;

                for (DualEntry<K,V> e = _entries[i]; e != null; e = e._next)
                {
                    e._mutable.setValue(null);
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

            onChange((K)key);
            
            final int hash = key.hashCode();
            final int index=hash & (_entries.length-1);
            
            for (DualEntry<K,V> e = _entries[index]; e != null; e = e._next) 
            {
                if (e._hash == hash && key.equals(e._key)) 
                {
                    V old=e._mutable.setValue(null);
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
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class MutableEntrySet extends AbstractSet<java.util.Map.Entry<K, V>>
    {
        @Override
        public Iterator<java.util.Map.Entry<K, V>> iterator()
        {
            return new MutableEntryIterator();
        }

        @Override
        public int size()
        {
            return _size;
        }
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class ImmutableEntrySet extends AbstractSet<java.util.Map.Entry<K, V>>
    {
        @Override
        public Iterator<java.util.Map.Entry<K, V>> iterator()
        {
            return new ImmutableEntryIterator();
        }

        @Override
        public int size()
        {
            return _size;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    static class DualEntry<K,V> implements Map.Entry<K,V>
    {
        private final ImmutableHashMap<K,V> _map;
        private final K _key;
        private final int _hash;
        private V _value;
        private DualEntry<K,V> _next;

        public K getKey()
        {
            return _key;
        }

        public V getValue()
        {
            if (_value instanceof ImmutableHashMap.Mutable)
                return (V)((ImmutableHashMap.Mutable)_value).asImmutable();
            else if (_value instanceof Map)
                return (V)Collections.unmodifiableMap((Map)_value);
            return _value;
        }

        public V setValue(V value)
        {
            throw new UnsupportedOperationException();
        }
        
        private Map.Entry<K,V> _mutable = new Map.Entry<K,V>()
        {
            public K getKey()
            {
                return _key;
            }

            public V getValue()
            {
                if (_value instanceof ImmutableHashMap)
                    return (V)((ImmutableHashMap)_value).asMutable();
                return _value;
            }

            public V setValue(V value)
            {
                _map.onChange(_key);
                
                V old = _value;
                _value = value;
                
                if (old!=null && _value==null)
                    _map._size--;
                else if (old==null && _value!=null)
                    _map._size++;
                
                return old;
            }
        };
        
        DualEntry(ImmutableHashMap<K,V> map,int hash, K k, V v) 
        {
            _map=map;
            _value = v;
            if (_value!=null)
                _map._size++;
            _key = k;
            _hash = hash;
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class EntryIterator 
    {
        protected int _index=0;
        protected DualEntry<K,V> _entry;
        protected DualEntry<K,V> _last;
        
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

        protected DualEntry<K, V> nextEntry()
        {
            if (_entry==null)
                throw new NoSuchElementException();
            DualEntry<K,V> entry=_entry;
            
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
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class ImmutableEntryIterator extends EntryIterator implements Iterator<java.util.Map.Entry<K,V>>
    {
        ImmutableEntryIterator()
        {}

        public Map.Entry<K, V> next()
        {
            return nextEntry();
        }

        public void remove()
        {
            throw new UnsupportedOperationException();
        }  
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class MutableEntryIterator extends EntryIterator implements Iterator<java.util.Map.Entry<K,V>>
    {
        MutableEntryIterator()
        {}

        public Map.Entry<K, V> next()
        {
            return nextEntry()._mutable;
        }

        public void remove()
        {
            if (_last==null)
                throw new NoSuchElementException();
            _last._mutable.setValue(null);
        }  
    }
}
