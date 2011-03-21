package org.cometd.javascript;

import java.util.HashMap;
import java.util.Map;

import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;

public class Utils
{
    private Utils()
    {
    }

    public static Object jsToJava(Object jsObject)
    {
        if (jsObject == null) return null;
        if (jsObject == org.mozilla.javascript.Context.getUndefinedValue()) return null;
        if (jsObject instanceof String) return jsObject;
        if (jsObject instanceof Boolean) return jsObject;
        if (jsObject instanceof Integer) return jsObject;
        if (jsObject instanceof Long) return jsObject;
        if (jsObject instanceof Float) return jsObject;
        if (jsObject instanceof Double) return jsObject;
        if (jsObject instanceof NativeArray) return convertArray((NativeArray) jsObject);
        if (jsObject instanceof NativeObject) return convertObject((NativeObject) jsObject);
        if (jsObject instanceof NativeJavaObject) return ((NativeJavaObject) jsObject).unwrap();
        return jsObject;
    }

    private static Object[] convertArray(NativeArray jsArray)
    {
        Object[] ids = jsArray.getIds();
        Object[] result = new Object[ids.length];
        for (int i = 0; i < ids.length; i++)
        {
            Object id = ids[i];
            int index = (Integer) id;
            Object jsValue = jsArray.get(index, jsArray);
            result[i] = jsToJava(jsValue);
        }
        return result;
    }

    private static Object convertObject(NativeObject jsObject)
    {
        Object[] ids = jsObject.getIds();
        Map result = new HashMap(ids.length);
        for (Object id : ids)
        {
            if (id instanceof String)
            {
                Object jsValue = jsObject.get((String) id, jsObject);
                result.put(id, jsToJava(jsValue));
            }
            else if (id instanceof Integer)
            {
                Object jsValue = jsObject.get((Integer) id, jsObject);
                result.put(id, jsToJava(jsValue));
            }
            else
                throw new AssertionError();
        }
        return result;
    }

    public static boolean isJavaScriptObject(Object object)
    {
        // Do not remove: called from JavaScript's env.js
        return object instanceof NativeObject;
    }
}
