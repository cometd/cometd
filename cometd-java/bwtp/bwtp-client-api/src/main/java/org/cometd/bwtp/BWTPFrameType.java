package org.cometd.bwtp;

import java.util.HashMap;
import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public enum BWTPFrameType
{
    HEADER("BWH"), MESSAGE("BWM");

    private final String code;

    private BWTPFrameType(String code)
    {
        this.code = code;
        BWTPFrameTypes.values.put(code, this);
    }

    public static BWTPFrameType from(String code)
    {
        return BWTPFrameTypes.values.get(code);
    }

    private static class BWTPFrameTypes
    {
        private static final Map<String, BWTPFrameType> values = new HashMap<String, BWTPFrameType>();
    }
}
