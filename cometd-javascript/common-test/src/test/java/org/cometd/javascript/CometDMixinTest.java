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

import org.junit.Test;

public class CometDMixinTest extends AbstractCometDTransportsTest {
    @Test
    public void testShallowMixin() throws Exception {
        evaluateScript("" +
                "var obj1 = {foo: {}};" +
                "var obj2 = cometd._mixin(false, {}, obj1);" +
                "" +
                "obj1.foo.bar = true;" +
                "window.assert(obj2.foo.bar === obj1.foo.bar);" +
                "" +
                "obj2.foo.baz = true;" +
                "window.assert(obj1.foo.baz === obj2.foo.baz);" +
                "");
    }

    @Test
    public void testDeepMixin() throws Exception {
        evaluateScript("" +
                "var obj1 = {foo: {}};" +
                "var obj2 = cometd._mixin(true, {}, obj1);" +
                "" +
                "obj1.foo.bar = true;" +
                "window.assert(obj2.foo.bar === undefined);" +
                "" +
                "obj2.foo.baz = true;" +
                "window.assert(obj1.foo.baz === undefined);" +
                "");
    }

    @Test
    public void testTargetParameterIsModified() throws Exception {
        evaluateScript("" +
                "var obj1 = {foo: true};" +
                "var obj2 = {bar: true};" +
                "var obj3 = cometd._mixin(true, obj1, obj2);" +
                "" +
                "window.assert(obj3.foo === true);" +
                "window.assert(obj3.bar === true);" +
                "window.assert(obj1.foo === true);" +
                "window.assert(obj1.bar === true);" +
                "window.assert(obj2.foo === undefined);" +
                "");
    }

    @Test
    public void testDeepMixinOfArrays1() throws Exception {
        evaluateScript("" +
                "var obj1 = {foo: [{bar:true}]};" +
                "var obj2 = {foo: [{baz:true}]};" +
                "var obj3 = cometd._mixin(true, {}, obj1, obj2);" +
                "" +
                "window.assert(obj3.foo.length === 1);" +
                "window.assert(obj3.foo[0].bar === true);" +
                "window.assert(obj3.foo[0].baz === true);" +
                "");
    }

    @Test
    public void testDeepMixinOfArrays2() throws Exception {
        evaluateScript("" +
                "var obj1 = {foo: [{bar:true}]};" +
                "var obj2 = {foo: [undefined, {baz:true}]};" +
                "var obj3 = cometd._mixin(true, {}, obj1, obj2);" +
                "window.console.info(obj3);" +
                "" +
                "window.assert(obj3.foo.length === 2);" +
                "window.assert(obj3.foo[0].bar === true);" +
                "window.assert(obj3.foo[1].baz === true);" +
                "");
    }

    @Test
    public void testShallowMixinOverwritesNestedObjects() throws Exception {
        evaluateScript("" +
                "var obj1 = {ext: {foo: true}};" +
                "var obj2 = {ext: {bar: true}};" +
                "var obj3 = cometd._mixin(false, {}, obj1, obj2);" +
                "" +
                "window.assert(obj3.ext.foo === undefined);" +
                "window.assert(obj3.ext.bar === true);" +
                "window.assert(obj1.ext.foo === true);" +
                "window.assert(obj1.ext.bar === undefined);" +
                "window.assert(obj2.ext.foo === undefined);" +
                "window.assert(obj2.ext.bar === true);" +
                "");
    }

    @Test
    public void testDeepMixinMergesNestedObjects() throws Exception {
        evaluateScript("" +
                "var obj1 = {ext: {foo: true}};" +
                "var obj2 = {ext: {bar: true}};" +
                "var obj3 = cometd._mixin(true, {}, obj1, obj2);" +
                "" +
                "window.assert(obj3.ext.foo === true);" +
                "window.assert(obj3.ext.bar === true);" +
                "window.assert(obj1.ext.foo === true);" +
                "window.assert(obj1.ext.bar === undefined);" +
                "window.assert(obj2.ext.foo === undefined);" +
                "window.assert(obj2.ext.bar === true);" +
                "");
    }
}
