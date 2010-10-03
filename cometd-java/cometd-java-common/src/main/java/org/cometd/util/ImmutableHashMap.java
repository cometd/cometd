package org.cometd.util;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.jetty.util.log.Log;


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
 * <p>
 * The mutable version of the map supports deep change detection, so that if 
 * a mutable map of mutable map of mutable maps is modified, then the {@link #onChange(Object)}
 * method is called for the map, the maps parent and the parents parent etc.
 * Because the parent hierarchy needs to tracked, map or maps are implemented with 
 * a copy on write semantic.  If map A is a value field of map B, is then added to map C
 * then  A in B is replaced with a copy of A in B.
 * </p>  
 * 
 * 
 * @param <K> The key type
 * @param <V> The key value
 */
public class ImmutableHashMap<K,V> extends AbstractMap<K, V> implements Map<K,V>
{
    private final ImmutableEntry<K,V>[] _entries;
    private final Mutable _mutable;
    private final ImmutableEntrySet _immutableSet;
    private final MutableEntrySet _mutableSet;
    private int _size;
    private MutableEntry<?,?> _parentEntry;


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
        _entries=new ImmutableEntry[capacity];
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
     * @param key The key to lookup.
     * @return The found entry.
     */
    public Map.Entry<K,V> getEntry(K key)
    {
        if (key == null)
            throw new IllegalArgumentException();

        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);

        for (ImmutableEntry<K,V> e = _entries[index]; e != null; e = e._next) 
        {
            if (e._hash == hash && key.equals(e._key)) 
                return e;
        }
        return null;
    }    

    /* ------------------------------------------------------------ */
    /** Get an entry reference.
     * <p>
     * The entries stored in the top level of the maps entry buckets
     * are never deleted, only nulled. Thus references returned to them
     * can be kept over calls to reset and used to directly access the
     * value without a hash lookup.
     * </p>
     * <p>
     * This method returns only such top level entries. 
     * </p>
     * @param key The key to lookup.
     * @return The found entry or null
     * @throws IllegalStateException if key is not a top level entry
     */
    public ImmutableEntry<K,V> getEntryReference(K key)
    {
        if (key == null)
            throw new IllegalArgumentException();

        final int hash = key.hashCode();
        final int index=hash & (_entries.length-1);

        ImmutableEntry<K,V> e = _entries[index];
        if (e!=null)
        {
            if (e._hash == hash && key.equals(e._key)) 
                return e;
            throw new IllegalStateException(key+" is not top level");
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

        for (ImmutableEntry<K,V> e = _entries[index]; e != null; e = e._next) 
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
        /* ------------------------------------------------------------ */
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

            for (ImmutableEntry<K,V> e = _entries[index]; e != null; e = e._next) 
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

            for (ImmutableEntry<K,V> e = _entries[index]; e != null; e = e._next) 
            {
                if (e._hash == hash && key.equals(e._key)) 
                    return e._mutable.getValue();
            }
            return null;
        }

        /* ------------------------------------------------------------ */
        /** Get an entry reference.
         * @param key The key to lookup.
         * @return The found entry
         */
        public Map.Entry<K,V> getEntry(K key)
        {
            if (key == null)
                throw new IllegalArgumentException();

            final int hash = key.hashCode();
            final int index=hash & (_entries.length-1);

            for (ImmutableEntry<K,V> e = _entries[index]; e != null; e = e._next) 
            {
                if (e._hash == hash && key.equals(e._key)) 
                    return e._mutable;
            }
            return null;
        }    

        /* ------------------------------------------------------------ */
        /** Get an entry reference.
         * <p>
         * The entries stored in the top level of the maps entry buckets
         * are never deleted, only nulled. Thus references returned to them
         * can be kept over calls to reset and used to directly access the
         * value without a hash lookup.
         * </p>
         * <p>
         * This method returns only such top level entries, or null if one
         * cannot be created. Entries that do not exist are created with
         * null values.
         * </p>
         * @param key The key to lookup.
         * @return The found entry
         * @throws IllegalStateException if key is not a top level entry.
         */
        public MutableEntry<K,V> getEntryReference(K key) throws IllegalStateException
        {
            if (key == null)
                throw new IllegalArgumentException();

            final int hash = key.hashCode();
            final int index=hash & (_entries.length-1);

            ImmutableEntry<K,V> e = _entries[index];
            if (e!=null)
            {
                if (e._hash == hash && key.equals(e._key)) 
                    return e._mutable;
                throw new IllegalStateException(key+" is not top level");
            }

            e = new ImmutableEntry<K,V>(ImmutableHashMap.this,hash,key,null);
            _entries[index] = e;

            return e._mutable;
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

            ImmutableEntry<K,V> last = null;
            for (ImmutableEntry<K,V> e = _entries[index]; e != null; e = e._next) 
            {
                if (e._hash == hash && key.equals(e._key)) 
                {
                    V old=e._mutable.setValue(value);
                    return old;
                }
                last=e;
            }

            ImmutableEntry<K,V> e = new ImmutableEntry<K,V>(ImmutableHashMap.this,hash,key,value);
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

                for (ImmutableEntry<K,V> e = _entries[i]; e != null; e = e._next)
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

            for (ImmutableEntry<K,V> e = _entries[index]; e != null; e = e._next) 
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
    public static class ImmutableEntry<K,V> implements Map.Entry<K,V>
    {
        private final ImmutableHashMap<K,V> _map;
        private final K _key;
        private final int _hash;
        private V _value;
        private ImmutableEntry<K,V> _next;
        private final MutableEntry<K,V> _mutable = new MutableEntry<K,V>(this);

        ImmutableEntry(ImmutableHashMap<K,V> map,int hash, K k, V v) 
        {
            _map=map;
            _key = k;
            _hash = hash;
            _mutable.setValue(v);
        }

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

        public MutableEntry<K,V> asMutable()
        {
            return _mutable;
        }

        @Override
        public String toString()
        {
            return super.toString()+"("+_key+","+_value+")";
        }

    }

    public static final class MutableEntry<K,V> implements Map.Entry<K,V>
    {
        final ImmutableEntry<K, V> _immutable;

        MutableEntry(ImmutableEntry<K, V> immutable)
        {
            _immutable=immutable;
        }

        public K getKey()
        {
            return _immutable._key;
        }

        public V getValue()
        {
            if (_immutable._value instanceof ImmutableHashMap)
                return (V)((ImmutableHashMap)(_immutable._value)).asMutable();
            return _immutable._value;
        }

        public V setValue(V value)
        {
            // Walk up the parent map chain and call onChange at each level
            ImmutableHashMap map = _immutable._map;
            while(map!=null)
            {
                map.onChange(_immutable._key);
                map=map._parentEntry==null?null:map._parentEntry._immutable._map;
            }

            // Was the old value a mutable hash map?
            V old = _immutable._value;
            if (old instanceof ImmutableHashMap.Mutable)
            {
                // yes - clear it's parent value
                ((ImmutableHashMap.Mutable)old).asImmutable()._parentEntry=null;
            }

            _immutable._value = value;
            
            // is the new value a mutable hash map
            if (value instanceof ImmutableHashMap.Mutable)
            {
                ImmutableHashMap.Mutable mutable = (ImmutableHashMap.Mutable)value;
                
                // Does it already have a parent?
                if (mutable.asImmutable()._parentEntry!=null)
                {
                    // Copy on write clone 
                    if (Log.isDebugEnabled())
                        Log.debug("COW {} in {}",
                                mutable.asImmutable()._parentEntry.getKey(),
                                mutable.asImmutable()._parentEntry._immutable);
                    Map<?, ?> copy = new HashMap(mutable); // TODO should this be a mutable map?
                    mutable.asImmutable()._parentEntry.setValue(copy);
                }
                
                // otherwise set this map as the parent.
                mutable.asImmutable()._parentEntry=this;
            }

            if (old!=null && _immutable._value==null)
                _immutable._map._size--;
            else if (old==null && _immutable._value!=null)
                _immutable._map._size++;

            return old;
        }

        public ImmutableEntry<K,V> asImmutable()
        {
            return _immutable;
        }

        @Override
        public String toString()
        {
            return super.toString()+"("+_immutable._key+","+_immutable._value+")";
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class EntryIterator 
    {
        protected int _index=0;
        protected ImmutableEntry<K,V> _entry;
        protected ImmutableEntry<K,V> _last;

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

        protected ImmutableEntry<K, V> nextEntry()
        {
            if (_entry==null)
                throw new NoSuchElementException();
            ImmutableEntry<K,V> entry=_entry;

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
