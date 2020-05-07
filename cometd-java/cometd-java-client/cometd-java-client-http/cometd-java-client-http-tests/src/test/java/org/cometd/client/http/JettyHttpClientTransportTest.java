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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.client.http.jetty.JettyAsyncClientTransport;
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
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class JettyHttpClientTransportTest {
    private static ClientTransportFactory BUFFERING_JETTY_TRANSPORT = JettyHttpClientTransportTest::newJettyClientTransport;
    private static ClientTransportFactory ASYNC_JETTY_TRANSPORT = JettyHttpClientTransportTest::newAsyncJettyClientTransport;
    
    @Parameters(name = "{index}: JettyClient: {0}")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]
                {
                    {JettyHttpClientTransport.class.getSimpleName(), BUFFERING_JETTY_TRANSPORT },
                    {JettyAsyncClientTransport.class.getSimpleName(), ASYNC_JETTY_TRANSPORT}
                }
        );
    }
    
    @FunctionalInterface
    private static interface ClientTransportFactory {
        HttpClientTransport create(Map<String, Object> options, HttpClient h);
    }
    
    private final ClientTransportFactory clientFactory;

    public JettyHttpClientTransportTest(String clientTransportName, ClientTransportFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

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
        ClientTransport transport = newClientTransport(httpClient);
        Assert.assertEquals("long-polling", transport.getName());
    }

    @Test
    public void testAccept() throws Exception {
        ClientTransport transport = newClientTransport(httpClient);
        Assert.assertTrue(transport.accept("1.0"));
    }

    @Test
    public void testSendWithResponse200() throws Exception {
        final long processingTime = 500;
        final ServerSocket serverSocket = new ServerSocket(0);
        final AtomicReference<Exception> serverException = new AtomicReference<>();
        Thread serverThread = new Thread() {
            @Override
            public void run() {
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
            }
        };
        serverThread.start();
        final String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try {
                final CountDownLatch latch = new CountDownLatch(1);
                HttpClientTransport transport = newClientTransport(httpClient);
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
        final long processingTime = 500;
        final ServerSocket serverSocket = new ServerSocket(0);
        final AtomicReference<Exception> serverException = new AtomicReference<>();
        Thread serverThread = new Thread() {
            @Override
            public void run() {
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
            }
        };
        serverThread.start();
        String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try {
                HttpClientTransport transport = newClientTransport(httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
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
                }, new ArrayList<Message.Mutable>());
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
        final String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        try {
            HttpClientTransport transport = newClientTransport(httpClient);
            final CountDownLatch latch = new CountDownLatch(1);
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
            }, new ArrayList<Message.Mutable>());

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        } finally {
            httpClient.stop();
        }
    }

    @Test
    public void testSendWithServerCrash() throws Exception {
        final long processingTime = 500;
        final ServerSocket serverSocket = new ServerSocket(0);
        final AtomicReference<Exception> serverException = new AtomicReference<>();
        Thread serverThread = new Thread() {
            @Override
            public void run() {
                try {
                    Socket socket = serverSocket.accept();

                    Thread.sleep(processingTime);

                    socket.close();
                } catch (Exception x) {
                    serverException.set(x);
                }
            }
        };
        serverThread.start();
        final String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try {
                HttpClientTransport transport = newClientTransport(httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
                transport.setURL(serverURL);
                transport.setCookieStore(new HttpCookieStore());
                transport.init();

                long start = System.nanoTime();
                transport.send(new TransportListener.Empty() {
                    @Override
                    public void onFailure(Throwable failure, List<? extends Message> messages) {
                        latch.countDown();
                    }
                }, new ArrayList<Message.Mutable>());
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
        final long timeout = 1000;
        final ServerSocket serverSocket = new ServerSocket(0);
        final AtomicReference<Exception> serverException = new AtomicReference<>();
        Thread serverThread = new Thread() {
            @Override
            public void run() {
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
            }
        };
        serverThread.start();
        final String serverURL = "http://localhost:" + serverSocket.getLocalPort();

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try {

                Map<String, Object> options = new HashMap<>();
                options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, timeout);
                HttpClientTransport transport = newClientTransport(options, httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
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
                }, new ArrayList<Message.Mutable>());

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
    
    private HttpClientTransport newClientTransport(Map<String, Object> options, HttpClient client) {
        return clientFactory.create(options, client);
    }

    private HttpClientTransport newClientTransport(HttpClient client) {
        return clientFactory.create(null, client);
    }

    private static HttpClientTransport newJettyClientTransport(Map<String, Object> options, HttpClient client) {
        return new JettyHttpClientTransport(options, client);
    }

    private static HttpClientTransport newAsyncJettyClientTransport(Map<String, Object> options, HttpClient client) {
        return new JettyAsyncClientTransport(options, client);
    }
}
