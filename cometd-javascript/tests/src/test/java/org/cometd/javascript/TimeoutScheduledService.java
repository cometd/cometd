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

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulate a slow channel. Schedule send task in a queue
 */
public class TimeoutScheduledService {

	private final int periodMs;
    private Throwable lastFail;
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private final Queue<Callable<Void>> runnables = new ConcurrentLinkedQueue<>();
    private boolean running = false;

    public TimeoutScheduledService(final int periodMs) {
        this.periodMs = periodMs;
    }

    public synchronized Throwable getLastFail() {
        return lastFail;
    }

    public synchronized void resetFail() {
        lastFail = null;
    }

    public synchronized void setFail(final Throwable x) {
        lastFail = x;
    }

    private synchronized void start() {
        running = true;
        executorService.schedule(() -> {
            final Callable<Void> task = runnables.remove();
            try {
                task.call();
                resetFail();
            } catch (final Throwable x) {
                setFail(x);
            }

            synchronized (this) {
                if (!runnables.isEmpty()) {
                    start();
                } else {
                    running = false;
                }
            }

        }, periodMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void schedule(final Callable<Void> batch) {
        runnables.add(batch);
        if (!running) {
            start();
        }
	}
}
