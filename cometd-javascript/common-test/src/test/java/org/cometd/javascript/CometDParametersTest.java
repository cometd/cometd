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
package org.cometd.javascript;

import org.junit.Assert;
import org.junit.Test;
import org.mozilla.javascript.JavaScriptException;

public class CometDParametersTest extends AbstractCometDTest {
    @Test
    public void testConfigureInitMissingURL() throws Exception {
        try {
            evaluateScript("cometd.configure();");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.configure({});");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.init();");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.init({});");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @Test
    public void testSubscribeMissingChannel() {
        try {
            evaluateScript("cometd.subscribe();");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.subscribe(cometd, '_debug');");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @Test
    public void testSubscribeMissingFunction() {
        try {
            evaluateScript("cometd.subscribe('/channel');");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.subscribe('/channel', {});");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.subscribe('/channel', {}, 0);");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @Test
    public void testUnsubscribeMissingSubscription() {
        try {
            evaluateScript("cometd.unsubscribe();");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.unsubscribe({});");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @Test
    public void testAddListenerMissingChannel() {
        try {
            evaluateScript("cometd.addListener();");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.addListener(cometd, '_debug');");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @Test
    public void testAddListenerMissingFunction() {
        try {
            evaluateScript("cometd.addListener('/channel');");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.addListener('/channel', {});");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.addListener('/channel', {}, 0);");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @Test
    public void testRemoveListenerMissingSubscription() {
        try {
            evaluateScript("cometd.removeListener();");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.removeListener({});");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }
    }

    @Test
    public void testPublishMissingChannel() {
        try {
            evaluateScript("cometd.publish();");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }

        try {
            evaluateScript("cometd.publish({});");
            Assert.fail();
        } catch (JavaScriptException ignored) {
        }
    }
}
