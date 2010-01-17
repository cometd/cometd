package org.cometd.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Transport;

public class AbstractTransport implements Transport
{
    private final String _name;
    protected final Set<String> _mutable = new HashSet<String>();
    protected final OptionMap _options = new OptionMap();
    
    protected AbstractTransport(String name)
    {
        _name=name;
    }

    public Set<String> getMutableOptions()
    {
        return Collections.unmodifiableSet(_mutable);
    }

    public String getName()
    {
        return _name;
    }

    public Map<String, Object> getOptions()
    {
        return _options;
    }
    
    protected class OptionMap extends HashMap<String,Object>
    {
        @Override
        public Object put(String key, Object value)
        {
            if (containsKey(key) && !_mutable.contains(key))
                throw new UnsupportedOperationException("!mutable: "+key);
            _mutable.add(key);
            return super.put(key,value);
        }

        @Override
        public Object remove(Object key)
        {
            if (!_mutable.contains(key))
                throw new UnsupportedOperationException("!mutable: "+key);
            return super.remove(key);
        }
    }

}
