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
package org.cometd.javascript;

import java.util.EnumSet;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDCrossOriginTest extends AbstractCometDTransportsTest {
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception {
        super.customizeContext(context);
        CrossOriginFilter filter = new CrossOriginFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometdServletPath + "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCrossOriginSupported(String transport) throws Exception {
        initCometDServer(transport);

        String crossOriginCometDURL = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({" +
                "url: '" + crossOriginCometDURL + "', " +
                "requestHeaders: { Origin: 'http://localhost:8080' }, " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       connectLatch.countDown(); " +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");

        Assertions.assertTrue(connectLatch.await(5000));

        disconnect();
    }
}
