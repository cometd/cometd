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

import java.io.Closeable;
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
import java.security.CodeSource;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
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
        System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), testName, context.getDisplayName());
    };
    private Path baseDir;
    private Closeable server;
    private HttpClient httpClient;
    private WebSocketClient wsClient;
    private WebSocketContainer wsContainer;
    private String cometdURI;

    @BeforeEach
    public void prepare() {
        baseDir = Paths.get(System.getProperty("basedir", System.getProperty("user.dir")));
    }

    private void start(Path webXML) throws Exception {
        Path testDir = baseDir.resolve("target/test-webapp/" + testName);
        removeDirectory(testDir);

        Path contextDir = testDir.resolve("webapp");
        Path webINF = Files.createDirectories(contextDir.resolve("WEB-INF"));
        Path classes = Files.createDirectory(webINF.resolve("classes"));
        Files.createDirectory(webINF.resolve("lib"));
        Files.copy(webXML, webINF.resolve("web.xml"));

        // CometD dependencies in a CometD web application.
        copyWebAppDependency(jakarta.inject.Inject.class, webINF);
        copyWebAppDependency(jakarta.annotation.PostConstruct.class, webINF);
        copyWebAppDependency(org.slf4j.Logger.class, webINF);
        copyWebAppDependency(org.apache.logging.slf4j.Log4jLogger.class, webINF);
        copyWebAppDependency(org.apache.logging.log4j.Level.class, webINF);
        copyWebAppDependency(org.apache.logging.log4j.core.LoggerContext.class, webINF);
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
        copyWebAppDependency(org.eclipse.jetty.ee10.servlets.CrossOriginFilter.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.util.Callback.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.util.ajax.JSON.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.ee10.websocket.api.WebSocketPolicy.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.ee10.websocket.common.WebSocketSession.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.ee10.websocket.client.WebSocketClient.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.core.client.WebSocketCoreClient.class, webINF);
        copyWebAppDependency(org.eclipse.jetty.websocket.core.Configuration.class, webINF);

        // Web application classes.
        Path testClasses = baseDir.resolve("target/test-classes/");
        String serviceClass = WebAppService.class.getName().replace('.', '/') + ".class";
        Path servicePath = classes.resolve(serviceClass);
        Files.createDirectories(servicePath.getParent());
        Files.copy(testClasses.resolve(serviceClass), servicePath);
        String jsonClientConfigClass = WebAppService.JSONClientConfig.class.getName().replace('.', '/') + ".class";
        Path jsonClientConfigPath = classes.resolve(jsonClientConfigClass);
        Files.copy(testClasses.resolve(jsonClientConfigClass), jsonClientConfigPath);
        String jsonServerConfigClass = WebAppService.JSONServerConfig.class.getName().replace('.', '/') + ".class";
        Path jsonServerConfigPath = classes.resolve(jsonServerConfigClass);
        Files.copy(testClasses.resolve(jsonServerConfigClass), jsonServerConfigPath);
        String customClass = WebAppService.Custom.class.getName().replace('.', '/') + ".class";
        Path customPath = classes.resolve(customClass);
        Files.copy(testClasses.resolve(customClass), customPath);
        String convertorClass = WebAppService.CustomConvertor.class.getName().replace('.', '/') + ".class";
        Path convertorPath = classes.resolve(convertorClass);
        Files.copy(testClasses.resolve(convertorClass), convertorPath);

        // Setup the server classpath, so that it does not conflict with the test classpath,
        // which has all dependencies that may confuse with the server (e.g. ServiceLoader).
        Collection<URL> serverClassPath = new HashSet<>();
        addServerDependency(jakarta.servlet.Servlet.class, serverClassPath);
        addServerDependency(jakarta.websocket.Session.class, serverClassPath);
        addServerDependency(jakarta.annotation.Resources.class, serverClassPath);
        addServerDependency(jakarta.annotation.security.RunAs.class, serverClassPath);
        addServerDependency(org.slf4j.Logger.class, serverClassPath);
        addServerDependency(org.apache.logging.slf4j.SLF4JServiceProvider.class, serverClassPath);
        addServerDependency(org.apache.logging.log4j.Logger.class, serverClassPath);
        addServerDependency(org.apache.logging.log4j.core.Appender.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.annotations.AnnotationConfiguration.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.client.HttpClient.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.http.HttpStatus.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.io.EndPoint.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.jndi.NamingContext.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.plus.webapp.PlusConfiguration.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.servlet.security.SecurityHandler.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.server.Server.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.servlet.ServletContextHandler.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.util.Callback.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.webapp.WebAppContext.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.websocket.api.Session.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.websocket.common.WebSocketSession.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.websocket.core.CoreSession.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.websocket.core.client.WebSocketCoreClient.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.websocket.core.server.WebSocketServerComponents.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainerProvider.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketContainer.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketConfiguration.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketConfiguration.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.ee10.websocket.servlet.WebSocketUpgradeFilter.class, serverClassPath);
        addServerDependency(org.eclipse.jetty.xml.XmlConfiguration.class, serverClassPath);
        addServerDependency(org.objectweb.asm.ClassVisitor.class, serverClassPath);

        // The server main class.
        Path serverDir = Files.createDirectory(testDir.resolve("server"));
        String serverMainClass = WebAppServer.class.getName().replace('.', '/') + ".class";
        Path serverMainPath = serverDir.resolve(serverMainClass);
        Files.createDirectories(serverMainPath.getParent());
        Files.copy(testClasses.resolve(serverMainClass), serverMainPath);
        serverClassPath.add(serverDir.toUri().toURL());
        URLClassLoader serverClassLoader = new URLClassLoader(serverClassPath.toArray(URL[]::new), getClass().getClassLoader().getParent());
        try (ThreadClassLoaderScope ignored = new ThreadClassLoaderScope(serverClassLoader)) {
            server = (Closeable)serverClassLoader.loadClass(WebAppServer.class.getName()).getConstructor().newInstance();
            cometdURI = (String)server.getClass().getDeclaredMethod("apply", Path.class).invoke(server, contextDir);
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

        LifeCycle.start(httpClient);
    }

    @AfterEach
    public void dispose() {
        LifeCycle.stop(httpClient);
        IO.close(server);
    }

    @Test
    public void testWebAppWithNativeJettyWebSocketTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/jetty-ws-web.xml"));

        BayeuxClient client = new BayeuxClient(cometdURI, new JettyWebSocketTransport(null, null, wsClient));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithWebSocketTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/jsr-ws-web.xml"));

        BayeuxClient client = new BayeuxClient(cometdURI, new WebSocketTransport(null, null, wsContainer));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithHTTPTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/http-web.xml"));

        BayeuxClient client = new BayeuxClient(cometdURI, new JettyHttpClientTransport(null, httpClient));
        subscribePublishReceive(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithService() throws Exception {
        start(baseDir.resolve("src/test/resources/http-service-web.xml"));

        BayeuxClient client = new BayeuxClient(cometdURI, new JettyHttpClientTransport(null, httpClient));
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Stream.of(WebAppService.HTTP_CHANNEL, WebAppService.JAVAX_WS_CHANNEL, WebAppService.JETTY_WS_CHANNEL)
                .map(channel -> remoteCall(client, channel, cometdURI))
                .reduce(CompletableFuture::allOf)
                .get()
                .get(5, TimeUnit.SECONDS);

        client.disconnect();
    }

    @Test
    public void testWebAppWithServiceWithCustomJSON() throws Exception {
        start(baseDir.resolve("src/test/resources/http-service-custom-web.xml"));

        BayeuxClient client = new BayeuxClient(cometdURI, new JettyHttpClientTransport(null, httpClient));
        client.setOption("jsonContext", new WebAppService.JSONClientConfig());
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        WebAppService.Custom custom = new WebAppService.Custom();
        custom.data = cometdURI;
        Stream.of(WebAppService.JAVAX_WS_CUSTOM_CHANNEL)
                .map(channel -> remoteCall(client, channel, custom))
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
        CodeSource codeSource = klass.getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            URL location = codeSource.getLocation();
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
    }

    private void copyPath(Path source, Path target) {
        try {
            Files.copy(source, target);
        } catch (FileAlreadyExistsException ignored) {
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    }

    private void addServerDependency(Class<?> klass, Collection<URL> serverClassPath) {
        CodeSource codeSource = klass.getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            serverClassPath.add(codeSource.getLocation());
        }
    }
}
