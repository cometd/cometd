package org.cometd.server.filter;

import java.lang.reflect.Array;
import java.util.regex.Pattern;

public class RegexFilter extends JSONDataFilter
{
    protected String[] _templates;
    protected String[] _replaces;
    protected transient Pattern[] _patterns;

    /**
     * Assumes the init object is an Array of 2 element Arrays:
     * [regex,replacement]. if the regex replacement string is null, then an
     * IllegalStateException is thrown if the pattern matches.
     */
    @Override
    public void init(Object init)
    {
        super.init(init);

        _templates=new String[Array.getLength(init)];
        _replaces=new String[_templates.length];

        for (int i=0; i < _templates.length; i++)
        {
            Object entry=Array.get(init,i);
            _templates[i]=(String)Array.get(entry,0);
            _replaces[i]=(String)Array.get(entry,1);
        }

        checkPatterns();
    }

    protected void checkPatterns()
    {
        // TODO replace this check with a terracotta transient init clause
        synchronized(this)
        {
            if (_patterns == null)
            {
                _patterns=new Pattern[_templates.length];
                for (int i=0; i < _patterns.length; i++)
                {
                    _patterns[i]=Pattern.compile(_templates[i]);
                }
            }
        }
    }

    @Override
    protected Object filterString(String string)
    {
        checkPatterns();

        for (int i=0; i < _patterns.length; i++)
        {
            if (_replaces[i] != null)
                string=_patterns[i].matcher(string).replaceAll(_replaces[i]);
            else if (_patterns[i].matcher(string).matches())
                throw new IllegalStateException("matched " + _patterns[i] + " in " + string);
        }
        return string;
    }
}
