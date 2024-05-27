/*
 * Copyright (c) 2008 the original author or authors.
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

package org.cometd.common;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.cometd.bayeux.Promise;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AsyncFoldLeftTest {
    @Test
    public void testZero() throws Exception {
        String zero = "123";

        Promise.Completable<String> promise1 = new Promise.Completable<>();
        AsyncFoldLeft.run(new String[0], zero, (result, element, loop) -> loop.proceed(result + element), promise1);
        Assertions.assertEquals(zero, promise1.get());

        Promise.Completable<String> promise2 = new Promise.Completable<>();
        AsyncFoldLeft.run(List.of(), zero, (result, element, loop) -> loop.proceed(result + element), promise2);
        Assertions.assertEquals(zero, promise2.get());

        Promise.Completable<String> promise3 = new Promise.Completable<>();
        AsyncFoldLeft.run(Set.of(), zero, (result, element, loop) -> loop.proceed(result + element), promise3);
        Assertions.assertEquals(zero, promise3.get());
    }

    @Test
    public void testSyncLoop() throws Exception {
        String expected = "123";
        // Split into single characters.
        List<String> elements = List.of(expected.split(""));
        Promise.Completable<String> promise = new Promise.Completable<>();
        AsyncFoldLeft.run(elements, "", (result, element, loop) -> loop.proceed(result + element), promise);
        String result = promise.get();
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testAsyncLoop() throws Exception {
        String expected = "123";
        // Split into single characters.
        String[] elements = expected.split("");
        Promise.Completable<String> promise = new Promise.Completable<>();
        AsyncFoldLeft.run(elements, "", (result, element, loop) -> new Thread(() -> {
            sleep(500);
            loop.proceed(result + element);
        }).start(), promise);
        String result = promise.get();
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testSyncLeave() throws Exception {
        Collection<String> elements = List.of("1", "2", "3");
        Promise.Completable<String> promise = new Promise.Completable<>();
        AsyncFoldLeft.run(elements, "", (result, element, loop) -> {
            if (!result.isEmpty()) {
                loop.leave(result);
            } else {
                loop.proceed(result + element);
            }
        }, promise);
        String result = promise.get();
        Assertions.assertEquals("1", result);
    }

    @Test
    public void testAsyncLeave() throws Exception {
        List<String> elements = List.of("1", "2", "3");
        Promise.Completable<String> promise = new Promise.Completable<>();
        AsyncFoldLeft.run(elements, "", (result, element, loop) -> {
            if (!result.isEmpty()) {
                new Thread(() -> {
                    sleep(500);
                    loop.leave(result);
                }).start();
            } else {
                loop.proceed(result + element);
            }
        }, promise);
        String result = promise.get();
        Assertions.assertEquals("1", result);
    }

    @Test
    public void testSyncFail() throws Exception {
        Throwable failure = new Throwable();
        List<String> elements = List.of("1", "2", "3");
        Promise.Completable<String> promise = new Promise.Completable<>();
        AsyncFoldLeft.run(elements, "", (result, element, loop) -> {
            if (!result.isEmpty()) {
                loop.fail(failure);
            } else {
                loop.proceed(result + element);
            }
        }, promise);
        try {
            promise.get();
            Assertions.fail();
        } catch (ExecutionException x) {
            Assertions.assertSame(failure, x.getCause());
        }
    }

    @Test
    public void testAsyncFail() throws Exception {
        Throwable failure = new Throwable();
        List<String> elements = List.of("1", "2", "3");
        Promise.Completable<String> promise = new Promise.Completable<>();
        AsyncFoldLeft.run(elements, "", (result, element, loop) -> {
            if (!result.isEmpty()) {
                new Thread(() -> {
                    sleep(500);
                    loop.fail(failure);
                }).start();
            } else {
                loop.proceed(result + element);
            }
        }, promise);
        try {
            promise.get();
            Assertions.fail();
        } catch (ExecutionException x) {
            Assertions.assertSame(failure, x.getCause());
        }
    }

    @Test
    public void testSyncFailThenProceed() throws Exception {
        Throwable failure = new Throwable();
        List<String> elements = Arrays.asList("1", "2", "3");
        Promise.Completable<String> resultPromise = new Promise.Completable<>();
        Promise.Completable<String> proceedPromise = new Promise.Completable<>();
        Promise.Completable<String> failurePromise = new Promise.Completable<>();
        AsyncFoldLeft.run(elements, "", (result, element, loop) -> {
            if ("1".equals(element)) {
                loop.proceed(result + element);
            } else if ("2".equals(element)) {
                loop.fail(failure);
                try {
                    loop.proceed(result + element);
                    proceedPromise.succeed(null);
                } catch (Throwable x) {
                    proceedPromise.fail(x);
                }
            } else {
                failurePromise.succeed(null);
            }
        }, resultPromise);
        ExecutionException x = Assertions.assertThrows(ExecutionException.class, resultPromise::get);
        Assertions.assertSame(failure, x.getCause());
        // The proceed() after the fail() must not throw.
        proceedPromise.get(5, TimeUnit.SECONDS);
        // Must not continue the loop after failure.
        Assertions.assertThrows(TimeoutException.class, () -> failurePromise.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testSyncFailThenLeave() throws Exception {
        Throwable failure = new Throwable();
        List<String> elements = Arrays.asList("1", "2", "3");
        Promise.Completable<String> resultPromise = new Promise.Completable<>();
        Promise.Completable<String> leavePromise = new Promise.Completable<>();
        Promise.Completable<String> failurePromise = new Promise.Completable<>();
        AsyncFoldLeft.run(elements, "", (result, element, loop) -> {
            if ("1".equals(element)) {
                loop.proceed(result + element);
            } else if ("2".equals(element)) {
                loop.fail(failure);
                try {
                    loop.leave(result + element);
                    leavePromise.succeed(null);
                } catch (Throwable x) {
                    leavePromise.fail(x);
                }
            } else {
                failurePromise.succeed(null);
            }
        }, resultPromise);
        ExecutionException x = Assertions.assertThrows(ExecutionException.class, resultPromise::get);
        Assertions.assertSame(failure, x.getCause());
        // The leave() after the fail() must not throw.
        leavePromise.get(5, TimeUnit.SECONDS);
        // Must not continue the loop after failure.
        Assertions.assertThrows(TimeoutException.class, () -> failurePromise.get(1, TimeUnit.SECONDS));
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException x) {
            throw new RuntimeException(x);
        }
    }
}
