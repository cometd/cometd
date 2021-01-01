/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import java.lang.annotation.Annotation;
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
class AnnotationProcessor {
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

    protected boolean processPostConstruct(Object bean) {
        if (bean == null) {
            return false;
        }

        List<Method> postConstructs = findLifeCycleMethods(bean, PostConstruct.class);

        boolean result = false;
        for (Method method : postConstructs) {
            invokePrivate(bean, method);
            result = true;
        }

        return result;
    }

    protected boolean processPreDestroy(Object bean) {
        if (bean == null) {
            return false;
        }

        List<Method> preDestroys = findLifeCycleMethods(bean, PreDestroy.class);

        boolean result = false;
        for (Method method : preDestroys) {
            try {
                invokePrivate(bean, method);
                result = true;
            } catch (RuntimeException x) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Exception while invoking @PreDestroy method " + method + ", ignoring", x);
                }
            }
        }

        return result;
    }

    private List<Method> findLifeCycleMethods(Object bean, Class<? extends Annotation> lifeCycle) {
        List<Class<?>> classes = new ArrayList<>();
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass()) {
            classes.add(c);
        }
        Collections.reverse(classes);

        List<Method> result = new ArrayList<>();
        for (int i = 0; i < classes.size(); ++i) {
            Class<?> c = classes.get(i);
            boolean foundInClass = false;
            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods) {
                Annotation annotation = method.getAnnotation(lifeCycle);
                if (annotation != null) {
                    if (foundInClass) {
                        throw new RuntimeException("Invalid @" + lifeCycle.getSimpleName() + " method " + method + ": another method with the same annotation exists");
                    }
                    foundInClass = true;
                    if (method.getReturnType() != Void.TYPE) {
                        throw new RuntimeException("Invalid @" + lifeCycle.getSimpleName() + " method " + method + ": it must have void return type");
                    }
                    if (method.getParameterTypes().length > 0) {
                        throw new RuntimeException("Invalid @" + lifeCycle.getSimpleName() + " method " + method + ": it must have no parameters");
                    }
                    if (Modifier.isStatic(method.getModifiers())) {
                        throw new RuntimeException("Invalid @" + lifeCycle.getSimpleName() + " method " + method + ": it must not be static");
                    }

                    // Check if the method has not been overridden.
                    boolean overridden = false;
                    for (int j = i + 1; j < classes.size(); ++j) {
                        try {
                            Class<?> sc = classes.get(j);
                            sc.getDeclaredMethod(method.getName(), method.getParameterTypes());
                            overridden = true;
                            break;
                        } catch (Exception ignored) {
                        }
                    }
                    if (!overridden) {
                        result.add(method);
                    }
                }
            }
        }
        Collections.reverse(result);
        return result;
    }

    protected List<Method> findAnnotatedMethods(Object bean, Class<? extends Annotation> annotationClass) {
        List<Class<?>> classes = new ArrayList<>();
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass()) {
            classes.add(c);
        }
        Collections.reverse(classes);

        List<Method> result = new ArrayList<>();
        for (int i = 0; i < classes.size(); ++i) {
            Class<?> c = classes.get(i);
            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods) {
                Annotation annotation = method.getAnnotation(annotationClass);
                if (annotation != null) {
                    // Check if the method has not been overridden.
                    boolean overridden = false;
                    for (int j = i + 1; j < classes.size(); ++j) {
                        try {
                            Class<?> sc = classes.get(j);
                            sc.getDeclaredMethod(method.getName(), method.getParameterTypes());
                            overridden = true;
                            break;
                        } catch (Exception ignored) {
                        }
                    }
                    if (!overridden) {
                        result.add(method);
                    }
                }
            }
        }
        Collections.reverse(result);
        return result;
    }

    protected List<String> processParameters(Method method) {
        List<String> result = new ArrayList<>();
        Annotation[][] parametersAnnotations = method.getParameterAnnotations();
        for (Annotation[] parameterAnnotations : parametersAnnotations) {
            for (Annotation parameterAnnotation : parameterAnnotations) {
                if (parameterAnnotation instanceof Param) {
                    result.add(((Param)parameterAnnotation).value());
                }
            }
        }
        return result;
    }

    protected Object invokePrivate(Object bean, Method method, Object... args) {
        boolean accessible = method.isAccessible();
        try {
            method.setAccessible(true);
            return method.invoke(bean, args);
        } catch (InvocationTargetException x) {
            throw new RuntimeException(x.getCause());
        } catch (Exception x) {
            throw new RuntimeException(x);
        } finally {
            method.setAccessible(accessible);
        }
    }

    protected static Object invokePublic(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException x) {
            throw x.getCause();
        }
    }

    protected static Object callPublic(Object target, Method method, Object... arguments) {
        try {
            return invokePublic(target, method, arguments);
        } catch (Throwable x) {
            Class<?> klass = target.getClass();
            Logger logger = LoggerFactory.getLogger(klass);
            logger.info("Exception while invoking " + klass + "#" + method.getName() + "()", x);
            return null;
        }
    }

    protected Object getField(Object bean, Field field) {
        boolean accessible = field.isAccessible();
        try {
            field.setAccessible(true);
            return field.get(bean);
        } catch (IllegalAccessException x) {
            throw new RuntimeException(x);
        } finally {
            field.setAccessible(accessible);
        }
    }

    protected void setField(Object bean, Field field, Object value) {
        boolean accessible = field.isAccessible();
        try {
            field.setAccessible(true);
            field.set(bean, value);
        } catch (IllegalAccessException x) {
            throw new RuntimeException(x);
        } finally {
            field.setAccessible(accessible);
        }
    }

    protected static void checkMethodsPublic(Object bean, Class<? extends Annotation> annotationClass) {
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass()) {
            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods) {
                Annotation annotation = method.getAnnotation(annotationClass);
                if (annotation != null) {
                    if (!Modifier.isPublic(method.getModifiers())) {
                        throw new IllegalArgumentException("@" + annotationClass.getSimpleName() + " method " +
                                method.getDeclaringClass().getName() + "." + method.getName() + "(...) must be public");
                    }
                }
            }
        }
    }

    protected static void checkSignaturesMatch(Method method, Class<?>[] expectedTypes, List<String> paramNames) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != expectedTypes.length + paramNames.size()) {
            throw new IllegalArgumentException("Wrong number of parameters in service method: " + method.getName() + "(...)." +
                    (paramTypes.length > 2 ? " Template parameters not annotated with @" + Param.class.getSimpleName() + " ?" : ""));
        }
        for (int i = 0; i < expectedTypes.length; ++i) {
            Class<?> expected = expectedTypes[i];
            if (expected == null) {
                continue;
            }
            Class<?> parameter = paramTypes[i];
            if (!parameter.isAssignableFrom(expected)) {
                throw new IllegalArgumentException("Parameter type " + parameter.getName() + " must be instead " +
                        expected.getName() + " in service method: " + method.getName() + "(...).");
            }
        }
        for (int i = 0; i < paramNames.size(); ++i) {
            Class<?> parameter = paramTypes[expectedTypes.length + i];
            if (!parameter.isAssignableFrom(String.class)) {
                throw new IllegalArgumentException("Template parameter '" + paramNames.get(i) + "' must be of type " +
                        String.class.getName() + " in service method: " + method.getName() + "(...).");
            }
        }
    }

    protected boolean processInjectables(Object bean, List<Object> injectables) {
        boolean result = false;
        for (Object injectable : injectables) {
            result |= processInjectable(bean, injectable);
        }
        return result;
    }

    protected boolean processInjectable(Object bean, Object injectable) {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass()) {
            Field[] fields = c.getDeclaredFields();
            for (Field field : fields) {
                if (field.getAnnotation(Inject.class) != null) {
                    if (field.getType().isAssignableFrom(injectable.getClass())) {
                        Object value = getField(bean, field);
                        if (value != null) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Avoid injection of field {} on bean {}, it's already injected with {}", field, bean, value);
                            }
                            continue;
                        }

                        setField(bean, field, injectable);
                        result = true;
                        if (logger.isDebugEnabled()) {
                            logger.debug("Injected {} to field {} on bean {}", injectable, field, bean);
                        }
                    }
                }
            }
        }

        List<Method> methods = findAnnotatedMethods(bean, Inject.class);
        for (Method method : methods) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1) {
                if (parameterTypes[0].isAssignableFrom(injectable.getClass())) {
                    invokePrivate(bean, method, injectable);
                    result = true;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Injected {} to method {} on bean {}", injectable, method, bean);
                    }
                }
            }
        }
        return result;
    }
}
