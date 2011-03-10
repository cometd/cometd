package org.cometd.common;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Transport;

public class AbstractTransport implements Transport
{
    private final String _name;
    private final Map<String, Object> _options;
    private String[] _prefix = new String[0];

    protected AbstractTransport(String name, Map<String, Object> options)
    {
        _name = name;
        _options = options == null ? new HashMap<String, Object>() : options;
    }

    public String getName()
    {
        return _name;
    }

    public Object getOption(String name)
    {
        Object value = _options.get(name);
        String prefix = null;
        for (String segment : _prefix)
        {
            prefix = prefix == null ? segment : (prefix + "." + segment);
            String key = prefix + "." + name;
            if (_options.containsKey(key))
                value = _options.get(key);
        }
        return value;
    }

    public void setOption(String name, Object value)
    {
        String prefix = getOptionPrefix();
        _options.put(prefix == null ? name : (prefix + "." + name), value);
    }

    public String getOptionPrefix()
    {
        String prefix = null;
        for (String segment : _prefix)
            prefix = prefix == null ? segment : (prefix + "." + segment);
        return prefix;
    }

    public void setOptionPrefix(String prefix)
    {
        _prefix = prefix.split("\\.");
    }

    public Set<String> getOptionNames()
    {
        Set<String> names = new HashSet<String>();
        for (String name : _options.keySet())
        {
            int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0)
                name = name.substring(lastDot + 1);
            names.add(name);
        }
        return names;
    }

    public String getOption(String option, String dftValue)
    {
        Object value = getOption(option);
        return (value == null) ? dftValue : value.toString();
    }

    public long getOption(String option, long dftValue)
    {
        Object value = getOption(option);
        if (value == null)
            return dftValue;
        if (value instanceof Number)
            return ((Number)value).longValue();
        return Long.parseLong(value.toString());
    }

    public int getOption(String option, int dftValue)
    {
        Object value = getOption(option);
        if (value == null)
            return dftValue;
        if (value instanceof Number)
            return ((Number)value).intValue();
        return Integer.parseInt(value.toString());
    }

    public boolean getOption(String option, boolean dftValue)
    {
        Object value = getOption(option);
        if (value == null)
            return dftValue;
        if (value instanceof Boolean)
            return (Boolean)value;
        return Boolean.parseBoolean(value.toString());
    }
}
