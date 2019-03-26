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
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
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
    public void prepare() throws Exception {
        baseDir = Paths.get(System.getProperty("basedir", System.getProperty("user.dir")));
    }

    private void start(Path webXML) throws Exception {
        Path contextDir = baseDir.resolve("target/test-webapp/" + testname.getMethodName());
        Path webINF = contextDir.resolve("WEB-INF");
        Path lib = webINF.resolve("lib");

        removeDirectory(contextDir);
        Files.createDirectories(lib);

        Files.copy(webXML, webINF.resolve("web.xml"));
        // Typical Jetty dependencies in a CometD web application.
        copyJar(HttpClient.class, lib);
        copyJar(HttpStatus.class, lib);
        copyJar(EndPoint.class, lib);
        copyJar(ObjectMBean.class, lib);
        copyJar(CrossOriginFilter.class, lib);
        copyJar(Callback.class, lib);
        copyJar(JSON.class, lib);

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
        test(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithWebSocketTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/jsr-ws-web.xml"));

        BayeuxClient client = new BayeuxClient("http://localhost:" + connector.getLocalPort() + contextPath + "/cometd", new WebSocketTransport(null, null, wsContainer));
        test(client);
        client.disconnect();
    }

    @Test
    public void testWebAppWithHTTPTransport() throws Exception {
        start(baseDir.resolve("src/test/resources/http-web.xml"));

        BayeuxClient client = new BayeuxClient("http://localhost:" + connector.getLocalPort() + contextPath + "/cometd", new JettyHttpClientTransport(null, httpClient));
        test(client);
        client.disconnect();
    }

    private void test(final BayeuxClient client) throws Exception {
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

    private void removeDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
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

    private void copyJar(Class<?> klass, Path target) throws Exception {
        URL location = klass.getProtectionDomain().getCodeSource().getLocation();
        Path source = Paths.get(location.toURI());
        Files.copy(source, target.resolve(source.getFileName()));
    }
}
