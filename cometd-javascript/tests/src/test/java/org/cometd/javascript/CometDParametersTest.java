/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDParametersTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testConfigureInitMissingURL(String transport) throws Exception {
        initCometDServer(transport);

        try {
            evaluateScript("cometd.configure();");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.configure({});");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.init();");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.init({});");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSubscribeMissingChannel(String transport) throws Exception {
        initCometDServer(transport);

        try {
            evaluateScript("cometd.subscribe();");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.subscribe(cometd, '_debug');");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSubscribeMissingFunction(String transport) throws Exception {
        initCometDServer(transport);

        try {
            evaluateScript("cometd.subscribe('/channel');");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.subscribe('/channel', {});");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.subscribe('/channel', {}, 0);");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUnsubscribeMissingSubscription(String transport) throws Exception {
        initCometDServer(transport);

        try {
            evaluateScript("cometd.unsubscribe();");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.unsubscribe({});");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAddListenerMissingChannel(String transport) throws Exception {
        initCometDServer(transport);

        try {
            evaluateScript("cometd.addListener();");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.addListener(cometd, '_debug');");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAddListenerMissingFunction(String transport) throws Exception {
        initCometDServer(transport);

        try {
            evaluateScript("cometd.addListener('/channel');");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.addListener('/channel', {});");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.addListener('/channel', {}, 0);");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRemoveListenerMissingSubscription(String transport) throws Exception {
        initCometDServer(transport);

        try {
            evaluateScript("cometd.removeListener();");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.removeListener({});");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testPublishMissingChannel(String transport) throws Exception {
        initCometDServer(transport);

        try {
            evaluateScript("cometd.publish();");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.publish({});");
            Assertions.fail();
        } catch (JavaScriptException ignored) {
        }
    }
}
