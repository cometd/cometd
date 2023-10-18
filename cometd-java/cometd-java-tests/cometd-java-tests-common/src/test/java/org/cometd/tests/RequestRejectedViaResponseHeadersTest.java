/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.tests;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import okhttp3.Response;
import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class RequestRejectedViaResponseHeadersTest extends AbstractClientServerTest {
    @Override
    protected void configure(ServletContextHandler context) {
        context.addFilter(RejectFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Override
    protected ClientTransport newClientTransport(Transport transport, Map<String, Object> options) {
        if (transport == Transport.JETTY_HTTP) {
            return new TestJettyHttpClientTransport(options, httpClient);
        }
        return super.newClientTransport(transport, options);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestRejectedViaResponseHeaders(Transport transport) throws Exception {
        assumeTrue(transport != Transport.JAVAX_WEBSOCKET);

        start(transport);

        switch (transport) {
            case ASYNC_HTTP: {
                httpClient.getRequestListeners().add(new Request.Listener.Adapter() {
                    @Override
                    public void onBegin(Request request) {
                        request.onResponseHeaders(response -> {
                            if (response.getHeaders().containsKey("X-Reject")) {
                                response.abort(new RejectedIOException());
                            }
                        });
                    }
                });
                break;
            }
            case OKHTTP_HTTP:
            case OKHTTP_WEBSOCKET: {
                okHttpClient = okHttpClient.newBuilder().addInterceptor(chain -> {
                    okhttp3.Request request = chain.request();
                    Response response = chain.proceed(request);
                    if (response.header("X-Reject") != null) {
                        throw new RejectedIOException();
                    }
                    return response;
                }).build();
                break;
            }
            case JETTY_WEBSOCKET: {
                wsClient.addBean(new Request.Listener.Adapter() {
                    @Override
                    public void onBegin(Request request) {
                        request.onResponseHeaders(response -> {
                            if (response.getHeaders().containsKey("X-Reject")) {
                                response.abort(new RejectedIOException());
                            }
                        });
                    }
                });
                break;
            }
            default: {
                break;
            }
        }

        CountDownLatch disconnectLatch = new CountDownLatch(1);
        BayeuxClient client = newBayeuxClient(transport);
        client.addTransportListener(new TransportListener() {
            @Override
            public void onFailure(Throwable failure, List<? extends Message> messages) {
                if (failure instanceof RejectedIOException) {
                    client.abort();
                    disconnectLatch.countDown();
                }
            }
        });

        CompletableFuture<Message> completable = new CompletableFuture<>();
        client.handshake(completable::complete);
        Message reply = completable.get(5, TimeUnit.SECONDS);

        assertFalse(reply.isSuccessful());
        assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));

        await().atMost(5, TimeUnit.SECONDS).until(client::isDisconnected);
    }

    public static class RejectFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) {
            HttpServletResponse response = (HttpServletResponse)servletResponse;
            response.setHeader("X-Reject", "true");
            // Do not forward the request.
        }

        @Override
        public void destroy() {
        }
    }

    public static class RejectedIOException extends IOException {
    }

    private static class TestJettyHttpClientTransport extends JettyHttpClientTransport {
        public TestJettyHttpClientTransport(Map<String, Object> options, HttpClient httpClient) {
            super(options, httpClient);
        }

        @Override
        protected void customize(Request request) {
            request.onResponseHeaders(response -> {
                if (response.getHeaders().containsKey("X-Reject")) {
                    response.abort(new RejectedIOException());
                }
            });
        }
    }
}
