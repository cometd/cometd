/*
 * Copyright (c) 2008-2014 the original author or authors.
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
package com.webtide.demo.auction;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

public class Demo
{
    public static void main(String[] args) throws Exception
    {
        new Demo().start(9090);
    }

    public void start(int port) throws Exception
    {
        Server server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        final AtomicReference<Path> warDirRef = new AtomicReference<>();
        Files.walkFileTree(Paths.get("target"), EnumSet.noneOf(FileVisitOption.class), 2, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                if (!dir.toString().startsWith("target/cometd-demo-"))
                    return FileVisitResult.CONTINUE;
                warDirRef.set(dir);
                return FileVisitResult.TERMINATE;
            }
        });
        Path warDir = warDirRef.get();
        if (warDir == null)
            throw new IllegalStateException("Could not find war to deploy");

        WebAppContext context = new WebAppContext(warDir.toString(), "/");
        server.setHandler(context);

        WebSocketServerContainerInitializer.configureContext(context);

        server.start();
    }
}
