package org.cometd.bayeux;

/**
 * @version $Revision$ $Date$
 */
public interface Struct
{
    Object get(Object key);

    interface Mutable extends Struct
    {
        Object put(String key, Object value);
    }
}
