/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.javascript;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The thread model object, that runs all javascript in a single thread to simulate browser's environment.
 */
public class JavaScriptThreadModel extends ScriptableObject implements Runnable, ThreadModel {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Thread thread = new Thread(this);
    private final BlockingQueue<FutureTask<Object>> queue = new LinkedBlockingQueue<>();
    private final ScriptableObject rootScope;
    private volatile boolean running;
    private volatile Context context;
    private AtomicInteger scripts = new AtomicInteger();

    public JavaScriptThreadModel() {
        this(null);
    }

    public JavaScriptThreadModel(ScriptableObject rootScope) {
        this.rootScope = rootScope;
    }

    @Override
    public void init() throws Exception {
        assert rootScope != null;
        queue.clear();
        running = true;
        thread.start();
    }

    @Override
    public void destroy() throws Exception {
        running = false;
        for (FutureTask<Object> task : queue) {
            task.cancel(false);
        }
        thread.interrupt();
        thread.join();
    }

    @Override
    public String getClassName() {
        return "JavaScriptThreadModel";
    }

    @Override
    public void run() {
        context = Context.enter();
        context.setGeneratingDebug(true);
        context.setGeneratingSource(true);
        context.setOptimizationLevel(-1);
        try {
            while (running) {
                FutureTask<Object> command = queue.take();
                command.run();
            }
        } catch (InterruptedException x) {
            // We've been destroyed, just exit
        } finally {
            Context.exit();
            context = null;
        }
    }

    @Override
    public Object evaluate(final URL url) throws IOException {
        FutureTask<Object> future = new FutureTask<>(new Callable<Object>() {
            @Override
            public Object call() throws IOException {
                return context.evaluateReader(rootScope, new InputStreamReader(url.openStream()), url.toExternalForm(), 1, null);
            }
        });
        submit(future);
        try {
            return future.get();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException x) {
            Throwable xx = x.getCause();
            if (xx instanceof IOException) {
                throw (IOException)xx;
            }
            if (xx instanceof Error) {
                throw (Error)xx;
            }
            throw (RuntimeException)xx;
        }
    }

    @Override
    public Object evaluate(final String scriptName, final String script) {
        FutureTask<Object> future = new FutureTask<>(new Callable<Object>() {
            @Override
            public Object call() {
                return context.evaluateString(rootScope, script, scriptName == null ? nextScriptName() : scriptName, 1, null);
            }
        });
        submit(future);
        try {
            return future.get();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException x) {
            Throwable xx = x.getCause();
            if (xx instanceof Error) {
                throw (Error)xx;
            }
            throw (RuntimeException)xx;
        }
    }

    private String nextScriptName() {
        return "script_" + scripts.incrementAndGet();
    }

    // Invoked from env.js
    public Object jsFunction_invoke(final Scriptable scope, final Scriptable thiz, final Function function) {
        return invoke(true, scope, thiz, function);
    }

    @Override
    public Object invoke(boolean sync, final Scriptable scope, final Scriptable thiz, final Function function, final Object... arguments) {
        Callable<Object> invocation = new Callable<Object>() {
            @Override
            public Object call() {
                return function.call(context, scope, thiz, arguments);
            }
        };
        return invoke(true, invocation);
    }

    @Override
    public Object invoke(boolean sync, final Scriptable scope, final Scriptable thiz, final String functionName, final Object... arguments) {
        Callable<Object> invocation = new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                try {
                    Object property = ScriptableObject.getProperty(thiz, functionName);
                    if (property instanceof Function) {
                        Function function = (Function)property;
                        if (logger.isDebugEnabled()) {
                            logger.debug("Invoking function {} => {}", functionName, function);
                        }
                        return function.call(context, scope, thiz, arguments);
                    } else {
                        logger.info("Could not invoke function {} => {}", functionName, property);
                        return null;
                    }
                } catch (Throwable x) {
                    logger.info("Exception while trying to invoke " + functionName, x);
                    throw x;
                }
            }
        };
        return invoke(sync, invocation);
    }

    private Object invoke(boolean sync, Callable<Object> invocation) {
        FutureTask<Object> future = new FutureTask<>(invocation);
        submit(future);

        if (!sync) {
            return null;
        }

        try {
            return future.get();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException x) {
            Throwable xx = x.getCause();
            if (xx instanceof Error) {
                throw (Error)xx;
            }
            throw (RuntimeException)xx;
        }
    }

    private void submit(FutureTask<Object> future) {
        if (Thread.currentThread() == thread) {
            future.run();
        } else {
            if (running) {
                queue.offer(future);
            } else {
                throw new RejectedExecutionException();
            }
        }
    }

    @Override
    public void define(final Class<? extends Scriptable> clazz) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        FutureTask<Object> future = new FutureTask<>(new Callable<Object>() {
            @Override
            public Object call() throws InvocationTargetException, IllegalAccessException, InstantiationException {
                ScriptableObject.defineClass(rootScope, clazz);
                return null;
            }
        });
        submit(future);
        try {
            future.get();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException x) {
            Throwable xx = x.getCause();
            if (xx instanceof InvocationTargetException) {
                throw (InvocationTargetException)xx;
            }
            if (xx instanceof IllegalAccessException) {
                throw (IllegalAccessException)xx;
            }
            if (xx instanceof InstantiationException) {
                throw (InstantiationException)xx;
            }
            if (xx instanceof Error) {
                throw (Error)xx;
            }
            throw (RuntimeException)xx;
        }
    }

    @Override
    public Object get(final String name) {
        FutureTask<Object> future = new FutureTask<>(new Callable<Object>() {
            @Override
            public Object call() {
                return rootScope.get(name, rootScope);
            }
        });
        submit(future);
        try {
            Object result = future.get();
            if (Context.getUndefinedValue().equals(result)) {
                return null;
            }
            if (Scriptable.NOT_FOUND.equals(result)) {
                throw new IllegalArgumentException("No object named " + name + " exists in scope");
            }
            return result;
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException x) {
            Throwable xx = x.getCause();
            if (xx instanceof Error) {
                throw (Error)xx;
            }
            throw (RuntimeException)xx;
        }
    }

    @Override
    public void remove(final String name) {
        FutureTask<Object> future = new FutureTask<>(new Callable<Object>() {
            @Override
            public Object call() {
                rootScope.delete(name);
                return null;
            }
        });
        submit(future);
        try {
            future.get();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException x) {
            Throwable xx = x.getCause();
            if (xx instanceof Error) {
                throw (Error)xx;
            }
            throw (RuntimeException)xx;
        }
    }
}
