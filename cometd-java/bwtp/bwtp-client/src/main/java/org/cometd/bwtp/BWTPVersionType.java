package org.cometd.bwtp;

import java.util.HashMap;
import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public enum BWTPVersionType
{
    BWTP_1_0("BWTP/1.0");

    public static BWTPVersionType from(String code)
    {
        return BWTPVersionTypes.values.get(code);
    }

    private final String code;

    private BWTPVersionType(String code)
    {
        this.code = code;
        BWTPVersionTypes.values.put(code, this);
    }

    public String getCode()
    {
        return code;
    }

    private static class BWTPVersionTypes
    {
        private static final Map<String, BWTPVersionType> values = new HashMap<String, BWTPVersionType>();
    }
}
