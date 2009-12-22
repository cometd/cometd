package org.cometd.bayeux;

import java.util.HashMap;

/**
 * @version $Revision$ $Date$
 */
public class StandardStruct extends HashMap<String, Object> implements Struct.Mutable
{
    // Just a type-safe wrapper of HashMap that can be seen as read-only through Struct
}
