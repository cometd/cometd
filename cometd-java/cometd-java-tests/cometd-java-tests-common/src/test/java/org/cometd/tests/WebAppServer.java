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
import java.nio.file.Path;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class WebAppServer implements Closeable {
    private Server server;

    public String apply(Path contextDir) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        ServerConnector connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        WebAppContext webApp = new WebAppContext();
        String contextPath = "/ctx";
        webApp.setContextPath(contextPath);
        webApp.setBaseResource(ResourceFactory.of(server).newResource(contextDir.toUri()));
        contexts.addHandler(webApp);
        server.start();

        return "http://localhost:" + connector.getLocalPort() + contextPath + "/cometd";
    }

    @Override
    public void close() {
        LifeCycle.stop(server);
    }
}
