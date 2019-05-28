/*
 * Copyright (c) 2008-2019 the original author or authors.
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
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.cometd.annotation.Service;
import org.cometd.annotation.server.RemoteCall;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.common.AbstractHttpClientTransport;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.common.JSONContext;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WebAppTest {
    @Rule
    public TestName testname = new TestName();

    private Path baseDir;
    private Server server;
    private ServerConnector connector;
    private String contextPath = "/ctx";
    private WebSocketClient wsClient;
    private WebSocketContainer wsContainer;
    private HttpClient httpClient;

    @Before
    public void prepare() {
        baseDir = Paths.get(System.getProperty("basedir", System.getProperty("user.dir")));
    }

    private void start(Path webXML) throws Exception {
        Path contextDir = baseDir.resolve("target/test-webapp/" + testname.getMethodName());
        removeDirectory(contextDir);
        Path webINF = Files.createDirectories(contextDir.resolve("WEB-INF"));
        Path classes = Files.createDirectory(webINF.resolve("classes"));
        Files.createDirectory(webINF.resolve("lib"));

        Files.copy(webXML, webINF.resolve("web.xml"));
        // CometD dependencies in a CometD web application.
        copyWebAppDependency(Service.class, webINF);
        copyWebAppDependency(RemoteCall.class, webINF);
        copyWebAppDependency(Message.class, webINF);
        copyWebAppDependency(ClientSession.class, webINF);
        copyWebAppDependency(BayeuxServer.class, webINF);
        copyWebAppDependency(JSONContext.class, webINF);
        copyWebAppDependency(BayeuxServerImpl.class, webINF);
        copyWebAppDependency(org.cometd.server.websocket.common.AbstractWebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.server.websocket.javax.WebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.server.websocket.jetty.JettyWebSocketTransport.class, webINF);
        copyWebAppDependency(BayeuxClient.class, webINF);
        copyWebAppDependency(AbstractHttpClientTransport.class, webINF);
        copyWebAppDependency(JettyHttpClientTransport.class, webINF);
        copyWebAppDependency(org.cometd.client.websocket.common.AbstractWebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.client.websocket.javax.WebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.client.websocket.jetty.JettyWebSocketTransport.class, webINF);
        // Jetty dependencies in a CometD web application.
        copyWebAppDependency(HttpClient.class, webINF);
        copyWebAppDependency(HttpStatus.class, webINF);
        copyWebAppDependency(EndPoint.class, webINF);
        copyWebAppDependency(ObjectMBean.class, webINF);
        copyWebAppDependency(CrossOriginFilter.class, webINF);
        copyWebAppDependency(Callback.class, webINF);
        copyWebAppDependency(JSON.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.api.WebSocketPolicy.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.common.WebSocketSession.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.client.WebSocketClient.class, webINF);
        // Application classes.
        Path testClasses = baseDir.resolve("target/test-classes/");
        String serviceClass = WebAppService.class.getName().replace('.', '/') + ".class";
        Path servicePath = classes.resolve(serviceClass);
        Files.createDirectories(servicePath.getParent());
        Files.copy(testClasses.resolve(serviceClass), servicePath);

        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        WebAppContext webApp = new WebAppContext();
        webApp.setContextPath(contextPath);
        webApp.setBaseResource(Resource.newResource(contextDir.toUri()));
        webApp.setConfigurations(new Configuration[]{
                new AnnotationConfiguration(),
                new WebXmlConfiguration(),
                new WebInfConfiguration(),
                new PlusConfiguration(),
                new MetaInfConfiguration(),
                new FragmentConfiguration(),
                new EnvConfiguration()});

        contexts.addHandler(webApp);

        wsClient = new WebSocketClient();
        server.addBean(wsClient);

        wsContainer = ContainerProvider.getWebSocketContainer();
        server.addBean(wsContainer);

        httpClient = new HttpClient();
        server.addBean(httpClient);

        server.start();
    }

    @After
    public void dispose() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testWebAppWithNativeJettyWebSocketTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/jetty-ws-web.xml"));

        BayeuxClient client = new BayeuxClient("http://localhost:" + connector.getLocalPort() + contextPath + "/cometd", new JettyWebSocketTransport(null, null, wsClient));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithWebSocketTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/jsr-ws-web.xml"));

        BayeuxClient client = new BayeuxClient("http://localhost:" + connector.getLocalPort() + contextPath + "/cometd", new WebSocketTransport(null, null, wsContainer));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithHTTPTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/http-web.xml"));

        BayeuxClient client = new BayeuxClient("http://localhost:" + connector.getLocalPort() + contextPath + "/cometd", new JettyHttpClientTransport(null, httpClient));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithService() throws Exception {
        start(baseDir.resolve("src/test/resources/http-service-web.xml"));

        String uri = "http://localhost:" + connector.getLocalPort() + contextPath + "/cometd";
        BayeuxClient client = new BayeuxClient(uri, new JettyHttpClientTransport(null, httpClient));
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Stream.of(WebAppService.HTTP_CHANNEL, WebAppService.JAVAX_WS_CHANNEL, WebAppService.JETTY_WS_CHANNEL)
                .map(channel -> remoteCall(client, channel, uri))
                .reduce((cf1, cf2) -> CompletableFuture.allOf(cf1, cf2))
                .get()
                .get(5, TimeUnit.SECONDS);

        client.disconnect();
    }

    private void subscribePublishReceive(final BayeuxClient client) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> {
            if (message.isSuccessful()) {
                client.batch(() -> {
                    ClientSessionChannel broadcast = client.getChannel("/foo");
                    broadcast.subscribe((c, m) -> latch.countDown());
                    broadcast.publish("data");
                });
            }
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private CompletableFuture<Void> remoteCall(BayeuxClient client, String channel, Object data) {
        CompletableFuture<Void> completable = new CompletableFuture<>();
        client.remoteCall(channel, data, response -> {
            if (response.isSuccessful() && data.equals(response.getData())) {
                completable.complete(null);
            } else {
                completable.completeExceptionally(new CompletionException(response.toString(), null));
            }
        });
        return completable;
    }

    private void removeDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private void copyWebAppDependency(Class<?> klass, Path target) throws Exception {
        URL location = klass.getProtectionDomain().getCodeSource().getLocation();
        Path source = Paths.get(location.toURI());
        if (Files.isDirectory(source)) {
            Files.walk(source).forEach(path -> {
                Path relative = source.relativize(path);
                Path destination = target.resolve("classes").resolve(relative);
                copyPath(path, destination);
            });
        } else {
            copyPath(source, target.resolve("lib").resolve(source.getFileName()));
        }
    }

    private void copyPath(Path source, Path target) {
        try {
            Files.copy(source, target);
        } catch (FileAlreadyExistsException ignored) {
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    }
}
