package org.cometd.java.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Base class for common functionality in annotation processors
 */
class AnnotationProcessor
{
    protected final Logger logger = Log.getLogger(getClass().getName());

    protected boolean processPostConstruct(Object bean)
    {
        if (bean == null)
            return false;

        List<Method> postConstructs = new ArrayList<Method>();
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass())
        {
            boolean foundInClass = false;
            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods)
            {
                PostConstruct postConstruct = method.getAnnotation(PostConstruct.class);
                if (postConstruct != null)
                {
                    if (foundInClass)
                        throw new RuntimeException("Invalid @PostConstruct method " + method + ": another method with the same annotation exists");
                    foundInClass = true;
                    if (method.getReturnType() != Void.TYPE)
                        throw new RuntimeException("Invalid @PostConstruct method " + method + ": it must have void return type");
                    if (method.getParameterTypes().length > 0)
                        throw new RuntimeException("Invalid @PostConstruct method " + method + ": it must have no parameters");
                    if (Modifier.isStatic(method.getModifiers()))
                        throw new RuntimeException("Invalid @PostConstruct method " + method + ": it must not be static");
                    postConstructs.add(method);
                }
            }
        }
        Collections.reverse(postConstructs);

        boolean result = false;
        for (Method method : postConstructs)
        {
            invokeMethod(bean, method);
            result = true;
        }

        return result;
    }

    protected boolean processPreDestroy(Object bean)
    {
        if (bean == null)
            return false;

        List<Method> preDestroys = new ArrayList<Method>();
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass())
        {
            boolean foundInClass = false;
            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods)
            {
                PreDestroy preDestroy = method.getAnnotation(PreDestroy.class);
                if (preDestroy != null)
                {
                    if (foundInClass)
                        throw new RuntimeException("Invalid @PreDestroy method " + method + ": another method with the same annotation exists");
                    foundInClass = true;
                    if (method.getReturnType() != Void.TYPE)
                        throw new RuntimeException("Invalid @PreDestroy method " + method + ": it must have void return type");
                    if (method.getParameterTypes().length > 0)
                        throw new RuntimeException("Invalid @PreDestroy method " + method + ": it must have no parameters");
                    if (Modifier.isStatic(method.getModifiers()))
                        throw new RuntimeException("Invalid @PreDestroy method " + method + ": it must not be static");
                    preDestroys.add(method);
                }
            }
        }

        boolean result = false;
        for (Method method : preDestroys)
        {
            try
            {
                invokeMethod(bean, method);
                result = true;
            }
            catch (RuntimeException x)
            {
                logger.debug("Exception while invoking @PreDestroy method " + method + ", ignoring", x);
            }
        }

        return result;
    }

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

    protected static boolean signaturesMatch(Class<?>[] candidate, Class<?>[] expected)
    {
        if (candidate.length != expected.length)
            return false;

        for (int i = 0; i < candidate.length; i++)
        {
            Class<?> parameter = candidate[i];
            if (!parameter.isAssignableFrom(expected[i]))
                return false;
        }

        return true;
    }
}
