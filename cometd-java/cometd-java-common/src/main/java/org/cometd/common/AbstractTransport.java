/*
 * Copyright (c) 2008-2017 the original author or authors.
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
package org.cometd.common;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Transport;

public class AbstractTransport implements Transport {
    private final String _name;
    private final Map<String, Object> _options;
    private String[] _prefix = new String[0];
    private String _optionPrefix = "";

    protected AbstractTransport(String name, Map<String, Object> options) {
        _name = name;
        _options = options == null ? new HashMap<String, Object>(1) : options;
    }

    @Override
    public String getName() {
        return _name;
    }

    /**
     * Returns an option value for the given option name, searching the option name tree.
     * The option map is searched for the option name with the most specific prefix.
     * If this transport was initialized with a call to:
     * <pre>
     *   setOptionPrefix("long-polling.jsonp");
     * </pre>
     * then a call to getOption("foobar") will look for the
     * most specific value with names:
     * <pre>
     *   long-polling.json.foobar
     *   long-polling.foobar
     *   foobar
     * </pre>
     *
     * @param name the option name to return the value for.
     */
    @Override
    public Object getOption(String name) {
        Object value = _options.get(name);
        String prefix = null;
        for (String segment : _prefix) {
            prefix = prefix == null ? segment : (prefix + "." + segment);
            String key = prefix + "." + name;
            if (_options.containsKey(key)) {
                value = _options.get(key);
            }
        }
        return value;
    }

    /**
     * Sets the option value with the given name.
     * The option name is inspected to see whether it starts with the {@link #getOptionPrefix() option prefix};
     * if it does not, the option prefix is prepended to the given name.
     *
     * @param name  the option name to set the value for.
     * @param value the value of the option.
     */
    public void setOption(String name, Object value) {
        String prefix = getOptionPrefix();
        if (prefix != null && prefix.length() > 0 && !name.startsWith(prefix)) {
            name = prefix + "." + name;
        }
        _options.put(name, value);
    }

    @Override
    public String getOptionPrefix() {
        return _optionPrefix;
    }

    /**
     * Set the option name prefix segment.
     * <p> Normally this is called by the super class constructors to establish
     * a naming hierarchy for options and iteracts with the {@link #setOption(String, Object)}
     * method to create a naming hierarchy for options.
     * For example the following sequence of calls:<pre>
     *   setOption("foo","x");
     *   setOption("bar","y");
     *   setOptionPrefix("long-polling");
     *   setOption("foo","z");
     *   setOption("whiz","p");
     *   setOptionPrefix("long-polling.jsonp");
     *   setOption("bang","q");
     *   setOption("bar","r");
     * </pre>
     * will establish the following option names and values:<pre>
     *   foo: x
     *   bar: y
     *   long-polling.foo: z
     *   long-polling.whiz: p
     *   long-polling.jsonp.bang: q
     *   long-polling.jsonp.bar: r
     * </pre>
     * The various {@link #getOption(String)} methods will search this
     * name tree for the most specific match.
     *
     * @param prefix the prefix name
     * @throws IllegalArgumentException if the new prefix is not prefixed by the old prefix.
     */
    public void setOptionPrefix(String prefix) {
        if (!prefix.startsWith(_optionPrefix)) {
            throw new IllegalArgumentException(_optionPrefix + " not prefix of " + prefix);
        }
        _optionPrefix = prefix;
        _prefix = prefix.split("\\.");
    }

    @Override
    public Set<String> getOptionNames() {
        Set<String> names = new HashSet<String>();
        for (String name : _options.keySet()) {
            int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                name = name.substring(lastDot + 1);
            }
            names.add(name);
        }
        return names;
    }

    /**
     * Get option or default value.
     *
     * @param option   The option name.
     * @param dftValue The default value.
     * @return option or default value
     * @see #getOption(String)
     */
    public String getOption(String option, String dftValue) {
        Object value = getOption(option);
        return (value == null) ? dftValue : value.toString();
    }

    /**
     * Get option or default value.
     *
     * @param option   The option name.
     * @param dftValue The default value.
     * @return option or default value
     * @see #getOption(String)
     */
    public long getOption(String option, long dftValue) {
        Object value = getOption(option);
        if (value == null) {
            return dftValue;
        }
        if (value instanceof Number) {
            return ((Number)value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    /**
     * Get option or default value.
     *
     * @param option   The option name.
     * @param dftValue The default value.
     * @return option or default value
     * @see #getOption(String)
     */
    public int getOption(String option, int dftValue) {
        Object value = getOption(option);
        if (value == null) {
            return dftValue;
        }
        if (value instanceof Number) {
            return ((Number)value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    /**
     * Get option or default value.
     *
     * @param option   The option name.
     * @param dftValue The default value.
     * @return option or default value
     * @see #getOption(String)
     */
    public boolean getOption(String option, boolean dftValue) {
        Object value = getOption(option);
        if (value == null) {
            return dftValue;
        }
        if (value instanceof Boolean) {
            return (Boolean)value;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
