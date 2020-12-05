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
package org.cometd.tests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.JndiConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;


public class WebAppTest {
    private String testName;
    @RegisterExtension
    final BeforeTestExecutionCallback printMethodName = context -> {
        testName = context.getRequiredTestMethod().getName();
        System.err.printf("Running %s.%s()%n", context.getRequiredTestClass().getSimpleName(), testName);
    };
    private Path baseDir;
    private AutoCloseable server;
    private int serverPort;
    private String contextPath = "/ctx";
    private WebSocketClient wsClient;
    private WebSocketContainer wsContainer;
    private HttpClient httpClient;

    @BeforeEach
    public void prepare() {
        baseDir = Paths.get(System.getProperty("basedir", System.getProperty("user.dir")));
    }

    private void start(Path webXML) throws Exception {
        // Setup the CometD application *.war file.
        Path contextDir = baseDir.resolve("target/test-webapp/" + testName);
        removeDirectory(contextDir);

        Path webINF = Files.createDirectories(contextDir.resolve("WEB-INF"));
        Path classes = Files.createDirectory(webINF.resolve("classes"));
        Files.createDirectory(webINF.resolve("lib"));
        Files.copy(webXML, webINF.resolve("web.xml"));
        // CometD dependencies in a CometD web application.
        copyWebAppDependency(org.cometd.annotation.Service.class, webINF);
        copyWebAppDependency(org.cometd.annotation.server.RemoteCall.class, webINF);
        copyWebAppDependency(org.cometd.bayeux.Message.class, webINF);
        copyWebAppDependency(org.cometd.bayeux.client.ClientSession.class, webINF);
        copyWebAppDependency(org.cometd.bayeux.server.BayeuxServer.class, webINF);
        copyWebAppDependency(org.cometd.common.JSONContext.class, webINF);
        copyWebAppDependency(org.cometd.server.BayeuxServerImpl.class, webINF);
        copyWebAppDependency(org.cometd.server.websocket.common.AbstractWebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.server.websocket.javax.WebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.server.websocket.jetty.JettyWebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.client.BayeuxClient.class, webINF);
        copyWebAppDependency(org.cometd.client.http.common.AbstractHttpClientTransport.class, webINF);
        copyWebAppDependency(org.cometd.client.http.jetty.JettyHttpClientTransport.class, webINF);
        copyWebAppDependency(org.cometd.client.websocket.common.AbstractWebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.client.websocket.javax.WebSocketTransport.class, webINF);
        copyWebAppDependency(org.cometd.client.websocket.jetty.JettyWebSocketTransport.class, webINF);
        // Jetty dependencies in a CometD web application.
        copyWebAppDependency(org.eclipse.jetty.client.HttpClient.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.http.HttpStatus.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.io.EndPoint.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.jmx.ObjectMBean.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.servlets.CrossOriginFilter.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.util.Callback.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.util.ajax.JSON.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.api.WebSocketPolicy.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.common.WebSocketSession.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.client.WebSocketClient.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.core.client.WebSocketCoreClient.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.core.Configuration.class, webINF);
        // Other dependencies in a CometD web application.
        copyWebAppDependency(javax.inject.Inject.class, webINF);
        copyWebAppDependency(javax.annotation.PostConstruct.class, webINF);
        // Web application classes.
        Path testClasses = baseDir.resolve("target/test-classes/");
        String serviceClass = WebAppService.class.getName().replace('.', '/') + ".class";
        Path servicePath = classes.resolve(serviceClass);
        Files.createDirectories(servicePath.getParent());
        Files.copy(testClasses.resolve(serviceClass), servicePath);

        // Setup the server classpath, so that it does not conflict with the test classpath,
        // which has all dependencies that may confuse with the server (e.g. ServiceLoader).
        List<URL> serverClassPath = new ArrayList<>();
        serverClassPath.add(testClasses.toUri().toURL());
        serverClassPath.add(org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.apache.logging.slf4j.SLF4JServiceProvider.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.apache.logging.log4j.Logger.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.apache.logging.log4j.core.Appender.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(javax.servlet.Servlet.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(javax.websocket.Session.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.annotations.AnnotationConfiguration.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.client.HttpClient.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.http.HttpStatus.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.io.EndPoint.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.jndi.NamingContext.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.security.SecurityHandler.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.server.Server.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.servlet.ServletContextHandler.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.util.Callback.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.webapp.WebAppContext.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.api.Session.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.core.CoreSession.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.core.client.WebSocketCoreClient.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.core.server.WebSocketServerComponents.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainerProvider.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketConfiguration.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.common.WebSocketSession.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.server.config.JettyWebSocketConfiguration.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.websocket.servlet.WebSocketUpgradeFilter.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.eclipse.jetty.xml.XmlConfiguration.class.getProtectionDomain().getCodeSource().getLocation());
        serverClassPath.add(org.objectweb.asm.ClassVisitor.class.getProtectionDomain().getCodeSource().getLocation());
        URLClassLoader serverClassLoader = new URLClassLoader(serverClassPath.toArray(URL[]::new), getClass().getClassLoader().getParent());
        // Need to setup the context class loader for ServiceLoader to work properly.
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(serverClassLoader);
        try {
            Class<?> serverClass = serverClassLoader.loadClass(JettyServer.class.getName());
            server = (AutoCloseable)serverClass.getConstructor(Path.class, String.class).newInstance(contextDir, contextPath);
            serverPort = (Integer)serverClass.getMethod("start").invoke(server);
        } finally {
            Thread.currentThread().setContextClassLoader(classLoader);
        }

        // Finally, setup the clients.
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        httpClient = new HttpClient();
        httpClient.setExecutor(clientThreads);
        wsClient = new WebSocketClient(httpClient);
        httpClient.addBean(wsClient);
        wsContainer = ContainerProvider.getWebSocketContainer();
        httpClient.addBean(wsContainer);
        httpClient.start();
    }

    @AfterEach
    public void dispose() throws Exception {
        LifeCycle.stop(httpClient);
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void testWebAppWithNativeJettyWebSocketTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/jetty-ws-web.xml"));
        BayeuxClient client = new BayeuxClient("http://localhost:" + serverPort + contextPath + "/cometd", new JettyWebSocketTransport(null, null, wsClient));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithWebSocketTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/jsr-ws-web.xml"));
        BayeuxClient client = new BayeuxClient("http://localhost:" + serverPort + contextPath + "/cometd", new WebSocketTransport(null, null, wsContainer));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithHTTPTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/http-web.xml"));
        BayeuxClient client = new BayeuxClient("http://localhost:" + serverPort + contextPath + "/cometd", new JettyHttpClientTransport(null, httpClient));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithService() throws Exception {
        start(baseDir.resolve("src/test/resources/http-service-web.xml"));
        String uri = "http://localhost:" + serverPort + contextPath + "/cometd";
        BayeuxClient client = new BayeuxClient(uri, new JettyHttpClientTransport(null, httpClient));
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Stream.of(WebAppService.HTTP_CHANNEL, WebAppService.JAVAX_WS_CHANNEL, WebAppService.JETTY_WS_CHANNEL)
                .map(channel -> remoteCall(client, channel, uri))
                .reduce(CompletableFuture::allOf)
                .get()
                .get(5, TimeUnit.SECONDS);

        client.disconnect();
    }

    private void subscribePublishReceive(BayeuxClient client) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> {
            if (message.isSuccessful()) {
                client.batch(() -> {
                    ClientSessionChannel broadcast = client.getChannel("/foo");
                    broadcast.subscribe((c, m) -> latch.countDown());
                    broadcast.publish("data");
                });
            }
        });
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
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

    public static class JettyServer implements AutoCloseable {
        private final Path contextDir;
        private final String contextPath;
        private Server server;

        public JettyServer(Path contextDir, String contextPath) {
            this.contextDir = contextDir;
            this.contextPath = contextPath;
        }

        public int start() throws Exception {
            QueuedThreadPool serverThreads = new QueuedThreadPool();
            serverThreads.setName("server");
            server = new Server(serverThreads);
            ServerConnector connector = new ServerConnector(server, 1, 1);
            server.addConnector(connector);
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            server.setHandler(contexts);

            WebAppContext webApp = new WebAppContext();
            webApp.setContextPath(contextPath);
            webApp.setBaseResource(Resource.newResource(contextDir.toUri()));
            webApp.addConfiguration(
                    new AnnotationConfiguration(),
                    new JavaxWebSocketConfiguration(),
                    new JettyWebSocketConfiguration(),
                    new JndiConfiguration()
            );
            contexts.addHandler(webApp);
            server.start();
            return connector.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            if (server != null) {
                server.stop();
            }
        }
    }
}
