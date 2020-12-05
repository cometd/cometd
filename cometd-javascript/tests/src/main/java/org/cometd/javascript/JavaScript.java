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

import java.net.URL;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaScript implements Runnable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Executors.privilegedThreadFactory());
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final BlockingQueue<FutureTask<?>> queue = new LinkedBlockingQueue<>();
    private final Thread thread = new Thread(this, "javascript");
    private final Context context;
    private final Value bindings;
    private volatile boolean running;

    public JavaScript() {
        context = Context.newBuilder("js").allowAllAccess(true).build();
        bindings = context.getBindings("js");
        bindings.putMember("javaScript", this);
    }

    public Value bindings() {
        return bindings;
    }

    public void init() {
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
        context.close();
        scheduler.shutdown();
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

    @SuppressWarnings("unchecked")
    public <T> T invoke(boolean sync, Object jsThis, Object function, Object... arguments) {
        FutureTask<T> task = new FutureTask<>(() -> {
            context.enter();
            try {
                Value self = Value.asValue(jsThis);
                Value funktion = Value.asValue(function);

                if (funktion.canExecute()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Invoking function {}", function);
                    }
                    return (T)funktion.getMember("call").execute(coalesce(self, arguments));
                }

                String name = funktion.asString();
                if (self.hasMember(name)) {
                    Value member = self.getMember(name);
                    if (member.canExecute()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Invoking object+function {}", name);
                        }
                        return (T)member.getMember("call").execute(coalesce(self, arguments));
                    } else {
                        logger.info("Member {} not a function in {}", name, jsThis);
                        return null;
                    }
                } else {
                    logger.info("{} not a member in {}", name, jsThis);
                    return null;
                }
            } catch (Throwable x) {
                logger.info("Exception while trying to invoke " + function, x);
                throw x;
            } finally {
                context.leave();
            }
        });
        submit(task);

        if (!sync) {
            return null;
        }

        return result(task);
    }

    private Object[] coalesce(Object item, Object... rest) {
        int length = rest.length;
        Object[] args = new Object[length + 1];
        args[0] = item;
        if (length > 0) {
            System.arraycopy(rest, 0, args, 1, length);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    public <T> T evaluate(URL url) {
        FutureTask<T> task = new FutureTask<>(() -> {
            context.enter();
            try {
                return (T)context.eval(Source.newBuilder("js", url).build());
            } catch (Throwable x) {
                throw new JavaScriptException(x);
            } finally {
                context.leave();
            }
        });
        return submitTask(task);
    }

    public <T> T evaluate(String name, String code) {
        FutureTask<T> task = new FutureTask<>(() -> {
            context.enter();
            try {
                return convert(context.eval(Source.newBuilder("js", code, name).build()));
            } catch (Throwable x) {
                throw new JavaScriptException(x);
            } finally {
                context.leave();
            }
        });
        return submitTask(task);
    }

    private <T> T submitTask(FutureTask<T> task) {
        submit(task);
        return result(task);
    }

    public <T> T get(String key) {
        FutureTask<T> task = new FutureTask<>(() -> {
            context.enter();
            try {
                return convert(bindings.getMember(key));
            } finally {
                context.leave();
            }
        });
        submit(task);
        return result(task);
    }

    public void put(String key, Object value) {
        bindings.putMember(key, value);
    }

    @SuppressWarnings("unchecked")
    private <T> T convert(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isHostObject()) {
            return value.asHostObject();
        }
        if (value.isBoolean()) {
            Boolean result = value.asBoolean();
            return (T)result;
        }
        if (value.isNumber()) {
            Double result = value.asDouble();
            return (T)result;
        }
        if (value.isString()) {
            return (T)value.asString();
        }
        return (T)value.as(Object.class);
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

    /**
     * Invoked from {@code browser.js}.
     *
     * @param jsThis the JavaScript {@code this} reference
     * @param function the function to invoke after the given delay
     * @param delay the delay after which the function is invoked, in milliseconds
     * @return a task representing the scheduled execution
     */
    public ScheduledFuture<?> schedule(Object jsThis, Object function, long delay) {
        return scheduler.schedule(() -> {
            invoke(false, jsThis, function);
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Invoked from {@code browser.js}.
     *
     * @param jsThis the JavaScript {@code this} reference
     * @param function the function to invoke after the given delay
     * @param initialDelay the delay of the first execution, in milliseconds
     * @param delay the delay after which the function is invoked, in milliseconds
     * @return a task representing the scheduled execution
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Object jsThis, Object function, long initialDelay, long delay) {
        return scheduler.scheduleWithFixedDelay(() -> invoke(false, jsThis, function), initialDelay, delay, TimeUnit.MILLISECONDS);
    }
}
