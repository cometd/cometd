package org.cometd.java.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

class AnnotationProcessor
{
    protected final Logger logger = Log.getLogger(getClass().getName());

    protected Object invokeMethod(Object bean, Method method, Object... args)
    {
        boolean accessible = method.isAccessible();
        try
        {
            method.setAccessible(true);
            return method.invoke(bean, args);
        }
        catch (InvocationTargetException x)
        {
            throw new RuntimeException(x.getCause());
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
        finally
        {
            method.setAccessible(accessible);
        }
    }

    protected Method findGetterMethod(Class<?> klass, Method setter)
    {
        try
        {
            String setterPrefix = "set";
            String setterName = setter.getName();
            if (setterName.startsWith(setterPrefix) &&
                    (setterName.length() == setterPrefix.length() ||
                            Character.isUpperCase(setterName.charAt(setterPrefix.length()))))
            {
                String getterName = "get" + setterName.substring(setterPrefix.length());
                Method getter = klass.getDeclaredMethod(getterName);
                if (getter.getReturnType() == setter.getParameterTypes()[0])
                    return getter;
            }
            return null;
        }
        catch (NoSuchMethodException x)
        {
            return null;
        }
    }

    protected Object getField(Object bean, Field field)
    {
        boolean accessible = field.isAccessible();
        try
        {
            field.setAccessible(true);
            return field.get(bean);
        }
        catch (IllegalAccessException x)
        {
            throw new RuntimeException(x);
        }
        finally
        {
            field.setAccessible(accessible);
        }
    }

    protected void setField(Object bean, Field field, Object value)
    {
        boolean accessible = field.isAccessible();
        try
        {
            field.setAccessible(true);
            field.set(bean, value);
        }
        catch (IllegalAccessException x)
        {
            throw new RuntimeException(x);
        }
        finally
        {
            field.setAccessible(accessible);
        }
    }
}
