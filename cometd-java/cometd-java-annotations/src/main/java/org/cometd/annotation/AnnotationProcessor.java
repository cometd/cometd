/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for common functionality in annotation processors
 */
class AnnotationProcessor
{
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

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

    protected boolean processInjectables(Object bean, List<Object> injectables)
    {
        boolean result = false;
        for (Object injectable : injectables)
            result |= processInjectable(bean, injectable);
        return result;
    }

    protected boolean processInjectable(Object bean, Object injectable)
    {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass())
        {
            Field[] fields = c.getDeclaredFields();
            for (Field field : fields)
            {
                if (field.getAnnotation(Inject.class) != null)
                {
                    if (field.getType().isAssignableFrom(injectable.getClass()))
                    {
                        Object value = getField(bean, field);
                        if (value != null)
                        {
                            logger.debug("Avoid injection of field {} on bean {}, it's already injected with {}", new Object[]{field, bean, value});
                            continue;
                        }

                        setField(bean, field, injectable);
                        result = true;
                        logger.debug("Injected {} to field {} on bean {}", new Object[]{injectable, field, bean});
                    }
                }
            }

            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods)
            {
                if (method.getAnnotation(Inject.class) != null)
                {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length == 1)
                    {
                        if (parameterTypes[0].isAssignableFrom(injectable.getClass()))
                        {
                            Method getter = findGetterMethod(c, method);
                            if (getter != null)
                            {
                                Object value = invokeMethod(bean, getter);
                                if (value != null)
                                {
                                    logger.debug("Avoid injection of method {} on bean {}, it's already injected with {}", new Object[]{method, bean, value});
                                    continue;
                                }
                            }

                            invokeMethod(bean, method, injectable);
                            result = true;
                            logger.debug("Injected {} to method {} on bean {}", new Object[]{injectable, method, bean});
                        }
                    }
                }
            }
        }
        return result;
    }
}
