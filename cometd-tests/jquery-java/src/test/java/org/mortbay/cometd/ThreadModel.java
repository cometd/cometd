package org.mortbay.cometd;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public interface ThreadModel
{
    void init();

    void destroy();

    Object evaluate(URL url) throws IOException;

    Object evaluate(String script);

    Object execute(Scriptable scope, Scriptable thiz, Function function, Object... arguments);

    void define(Class clazz) throws InvocationTargetException, IllegalAccessException, InstantiationException;

    Object get(String name);
}
