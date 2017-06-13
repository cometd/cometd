/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.eclipse.jetty.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaScript implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final BlockingQueue<FutureTask<?>> queue = new LinkedBlockingQueue<>();
    private final Thread thread = new Thread(this);
    private final ScriptEngine engine;
    private volatile boolean running;

    public JavaScript(ScriptEngine engine) {
        this.engine = engine;
    }

    public void init() throws Exception {
        queue.clear();
        running = true;
        thread.start();
    }

    public void destroy() throws Exception {
        running = false;
        for (FutureTask<?> task : queue) {
            task.cancel(false);
        }
        thread.interrupt();
        thread.join();
        engine.getBindings(ScriptContext.ENGINE_SCOPE).clear();
    }

    @Override
    public void run() {
        try {
            while (running) {
                FutureTask<?> task = queue.take();
                task.run();
            }
        } catch (InterruptedException x) {
            // We've been destroyed, just exit
        }
    }

    public <T> T invoke(boolean sync, ScriptObjectMirror thiz, Object function, Object... arguments) {
        FutureTask<T> task = new FutureTask<>(() -> {
            try {
                if (function instanceof ScriptObjectMirror) {
                    return (T)((ScriptObjectMirror)function).call(thiz, arguments);
                } else {
                    String name = function.toString();
                    if (thiz.hasMember(name)) {
                        Object member = thiz.getMember(name);
                        if (member instanceof ScriptObjectMirror) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Invoking function {}", name);
                            }
                            return (T)((ScriptObjectMirror)member).call(thiz, arguments);
                        } else {
                            logger.info("Function {} not defined in {}", name, thiz);
                            return null;
                        }
                    } else {
                        logger.info("Function {} not a member of {}", name, thiz);
                        return null;
                    }
                }
            } catch (Throwable x) {
                logger.info("Exception while trying to invoke " + function, x);
                throw x;
            }
        });
        submit(task);

        if (!sync) {
            return null;
        }

        return result(task);
    }

    public <T> T evaluate(URL url) {
        try {
            return evaluate(url.toExternalForm(), IO.toString(url.openStream()));
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    }

    public <T> T evaluate(String name, String code) {
        String script = name == null ? code : String.format("//# sourceURL=%s%n%s", name, code);
        FutureTask<T> task = new FutureTask<>(() -> {
            try {
                return (T)engine.eval(script);
            } catch (ScriptException x) {
                throw new JavaScriptException(x);
            }
        });
        submit(task);
        return result(task);
    }

    public <T> T get(String key) {
        FutureTask<T> task = new FutureTask<>(() -> (T)engine.get(key));
        submit(task);
        return result(task);
    }

    private void submit(FutureTask<?> task) {
        if (Thread.currentThread() == thread) {
            task.run();
        } else {
            if (running) {
                queue.offer(task);
            } else {
                throw new RejectedExecutionException();
            }
        }
    }

    private <T> T result(FutureTask<T> task) {
        try {
            return task.get();
        } catch (InterruptedException x) {
            return null;
        } catch (ExecutionException x) {
            Throwable xx = x.getCause();
            if (xx instanceof Error) {
                throw (Error)xx;
            }
            throw (RuntimeException)xx;
        }
    }
}
