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
package org.cometd.client.http;

import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.common.HashMapMessage;
import org.cometd.common.TransportException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.HttpCookieStore;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class JettyHttpClientTransportTest {
    @Rule
    public final TestWatcher testName = new TestWatcher() {
        @Override
        protected void starting(Description description) {
            super.starting(description);
            System.err.printf("Running %s.%s%n", description.getTestClass().getName(), description.getMethodName());
        }
    };
    private HttpClient httpClient;

    @Before
    public void prepare() throws Exception {
        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void dispose() throws Exception {
        httpClient.stop();
    }

    @Test
    public void testType() {
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        Assert.assertEquals("long-polling", transport.getName());
    }

    @Test
    public void testAccept() {
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        Assert.assertTrue(transport.accept("1.0"));
    }

    @Test
    public void testSendWithResponse200() throws Exception {
        long processingTime = 500;
        ServerSocket serverSocket = new ServerSocket(0);
        AtomicReference<Exception> serverException = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                Socket socket = serverSocket.accept();

                Thread.sleep(processingTime);

                OutputStream output = socket.getOutputStream();
                output.write((
                        "HTTP/1.1 200 OK\r\n" +
                                "Connection: close\r\n" +
                                "Content-Type: application/json;charset=UTF-8\r\n" +
                                "Content-Length: 2\r\n" +
                                "\r\n" +
                                "[]").getBytes(StandardCharsets.UTF_8));
                output.flush();
                socket.close();
            } catch (Exception x) {
                serverException.set(x);
            }
        });
        serverThread.start();
        String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try {
                CountDownLatch latch = new CountDownLatch(1);
                HttpClientTransport transport = new JettyHttpClientTransport(null, httpClient);
                transport.setURL(serverURL);
                transport.setCookieStore(new HttpCookieStore());
                transport.init();

                List<Message.Mutable> messages = new ArrayList<>(1);
                messages.add(new HashMapMessage());
                long start = System.nanoTime();
                transport.send(new TransportListener.Empty() {
                    @Override
                    public void onMessages(List<Message.Mutable> messages) {
                        latch.countDown();
                    }
                }, messages);
                long end = System.nanoTime();

                long elapsed = TimeUnit.NANOSECONDS.toMillis(end - start);
                Assert.assertTrue("elapsed=" + elapsed + ", processing=" + processingTime, elapsed <= processingTime);
                Assert.assertTrue(latch.await(2 * processingTime, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                httpClient.stop();
            }
        } finally {
            serverThread.join();
            Assert.assertNull(serverException.get());
            serverSocket.close();
        }
    }

    @Test
    public void testSendWithResponse500() throws Exception {
        long processingTime = 500;
        ServerSocket serverSocket = new ServerSocket(0);
        AtomicReference<Exception> serverException = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                Socket socket = serverSocket.accept();

                Thread.sleep(processingTime);

                OutputStream output = socket.getOutputStream();
                output.write((
                        "HTTP/1.1 500 Internal Server Error\r\n" +
                                "Connection: close\r\n" +
                                "\r\n").getBytes(StandardCharsets.UTF_8));
                output.flush();

                socket.close();
            } catch (Exception x) {
                serverException.set(x);
            }
        });
        serverThread.start();
        String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try {
                HttpClientTransport transport = new JettyHttpClientTransport(null, httpClient);
                CountDownLatch latch = new CountDownLatch(1);
                transport.setURL(serverURL);
                transport.setCookieStore(new HttpCookieStore());
                transport.init();

                long start = System.nanoTime();
                transport.send(new TransportListener.Empty() {
                    @Override
                    public void onFailure(Throwable failure, List<? extends Message> messages) {
                        if (failure instanceof TransportException) {
                            latch.countDown();
                        }
                    }
                }, new ArrayList<>());
                long end = System.nanoTime();

                long elapsed = TimeUnit.NANOSECONDS.toMillis(end - start);
                Assert.assertTrue("elapsed=" + elapsed + ", processing=" + processingTime, elapsed <= processingTime);
                Assert.assertTrue(latch.await(2000 + 2 * processingTime, TimeUnit.MILLISECONDS));
            } finally {
                httpClient.stop();
            }
        } finally {
            serverThread.join();
            Assert.assertNull(serverException.get());
            serverSocket.close();
        }
    }

    @Test
    public void testSendWithServerDown() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        serverSocket.close();
        String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        try {
            HttpClientTransport transport = new JettyHttpClientTransport(null, httpClient);
            CountDownLatch latch = new CountDownLatch(1);
            transport.setURL(serverURL);
            transport.setCookieStore(new HttpCookieStore());
            transport.init();

            transport.send(new TransportListener.Empty() {
                @Override
                public void onFailure(Throwable failure, List<? extends Message> messages) {
                    if (failure instanceof ConnectException) {
                        latch.countDown();
                    }
                }
            }, new ArrayList<>());

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        } finally {
            httpClient.stop();
        }
    }

    @Test
    public void testSendWithServerCrash() throws Exception {
        long processingTime = 500;
        ServerSocket serverSocket = new ServerSocket(0);
        AtomicReference<Exception> serverException = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                Socket socket = serverSocket.accept();

                Thread.sleep(processingTime);

                socket.close();
            } catch (Exception x) {
                serverException.set(x);
            }
        });
        serverThread.start();
        String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try {
                HttpClientTransport transport = new JettyHttpClientTransport(null, httpClient);
                CountDownLatch latch = new CountDownLatch(1);
                transport.setURL(serverURL);
                transport.setCookieStore(new HttpCookieStore());
                transport.init();

                long start = System.nanoTime();
                transport.send(new TransportListener.Empty() {
                    @Override
                    public void onFailure(Throwable failure, List<? extends Message> messages) {
                        latch.countDown();
                    }
                }, new ArrayList<>());
                long end = System.nanoTime();

                long elapsed = TimeUnit.NANOSECONDS.toMillis(end - start);
                Assert.assertTrue("elapsed=" + elapsed + ", processing=" + processingTime, elapsed <= processingTime);
                Assert.assertTrue(latch.await(2 * processingTime, TimeUnit.MILLISECONDS));
            } finally {
                httpClient.stop();
            }
        } finally {
            serverThread.join();
            Assert.assertNull(serverException.get());
            serverSocket.close();
        }
    }

    @Test
    public void testSendWithServerExpire() throws Exception {
        long timeout = 1000;
        ServerSocket serverSocket = new ServerSocket(0);
        AtomicReference<Exception> serverException = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                Socket socket = serverSocket.accept();

                Thread.sleep(2 * timeout);

                OutputStream output = socket.getOutputStream();
                output.write((
                        "HTTP/1.1 200 OK\r\n" +
                                "Connection: close\r\n" +
                                "Content-Type: application/json;charset=UTF-8\r\n" +
                                "Content-Length: 2\r\n" +
                                "\r\n" +
                                "[]").getBytes(StandardCharsets.UTF_8));
                output.flush();

                socket.close();
            } catch (Exception x) {
                serverException.set(x);
            }
        });
        serverThread.start();
        String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try {

                Map<String, Object> options = new HashMap<>();
                options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, timeout);
                HttpClientTransport transport = new JettyHttpClientTransport(options, httpClient);
                CountDownLatch latch = new CountDownLatch(1);
                transport.setURL(serverURL);
                transport.setCookieStore(new HttpCookieStore());
                transport.init();

                transport.send(new TransportListener.Empty() {
                    @Override
                    public void onFailure(Throwable failure, List<? extends Message> messages) {
                        if (failure instanceof TimeoutException) {
                            latch.countDown();
                        }
                    }
                }, new ArrayList<>());

                Assert.assertTrue(latch.await(2 * timeout, TimeUnit.MILLISECONDS));
            } finally {
                httpClient.stop();
            }
        } finally {
            serverThread.join();
            Assert.assertNull(serverException.get());
            serverSocket.close();
        }
    }
}
