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

import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GraalJSTest {
    @Test
    public void testRootBindings() {
        try (Context context = Context.newBuilder("js").build()) {
            withContext(context, () -> {
                Value bindings = context.getBindings("js");
                Object object = new Object();
                bindings.putMember("root", object);
                Value result = context.eval("js", "root");
                Assertions.assertSame(object, result.asHostObject());
            });
        }
    }

    @Test
    public void testFunctionPassing() {
        // Need allowAllAccess(true) to see Java objects in JavaScript.
        try (Context context = Context.newBuilder("js").allowAllAccess(true).build()) {
            withContext(context, () -> {
                Value bindings = context.getBindings("js");
                // Note the use of Value.asValue() to convert from PolyglotMapAndFunction to Value.
                Function<Object, Integer> function = value -> Value.asValue(value).execute().asInt();
                bindings.putMember("fn", function);
                Value result = context.eval("js", "fn.apply(function() { return 42; });");
                Assertions.assertEquals(42, result.asInt());
            });
        }
    }

    @Test
    public void testThisInsideFunction() {
        try (Context context = Context.newBuilder("js").allowAllAccess(true).build()) {
            withContext(context, () -> {
//                Value obj = context.eval("js", "{ \"exec\": function() { return this; } }");
                Value obj = context.eval("js", "({ " +
                        "  self: {}," +
                        "  exec: function(arg) { if (arg === true) { return this; } } " +
                        "})");
                Value jsThis = obj.getMember("self");
                Object[] args = new Object[]{Boolean.TRUE};
                Value result1 = obj.getMember("exec").execute(args);
                Assertions.assertEquals(obj.toString(), result1.toString());
                Value result2 = obj.getMember("exec").getMember("apply").execute(jsThis, args);
                Assertions.assertEquals(jsThis.toString(), result2.toString());
            });
        }
    }

    @Test
    public void testPrototype() {
        try (Context context = Context.newBuilder("js").allowAllAccess(true).build()) {
            withContext(context, () -> {
                Value result = context.eval("js", "" +
                        "var W = {};" +
                        "W.A = function() {};" +
                        "W.A.prototype = function() {" +
                        "  return {" +
                        "    run: function() { return 'SIMON'; }," +
                        "    get foo() { return 'FOO'; }" +
                        "  };" +
                        "}();" +
                        "var a = new W.A();" +
                        "console.log(W.A.prototype);" +
                        "a.foo + a.run();" +
                        "");
                System.err.println("result = " + result);
            });
        }
    }

    @Test
    public void testForLoop() {
        try (Context context = Context.newBuilder("js").allowAllAccess(true).build()) {
            withContext(context, () -> {
                context.eval("js", "" +
                        "var obj = new (function A(){})();" +
                        "for (var n in obj) {" +
                        "  printErr(n);" +
                        "}");
            });
        }
    }

    @Test
    public void testBinary() {
        try (Context context = Context.newBuilder("js").allowAllAccess(true).build()) {
            withContext(context, () -> {
                context.eval("js", "" +
                        "var buffer = new ArrayBuffer(16);" +
                        "var view1 = new DataView(buffer);" +
                        "for (var d = 0; d < view1.byteLength; ++d) {" +
                        "    view1.setUint8(d, d);" +
                        "}");
            });
        }
    }

    private void withContext(Context context, Runnable lambda) {
        context.enter();
        try {
            lambda.run();
        } finally {
            context.leave();
        }
    }
}
