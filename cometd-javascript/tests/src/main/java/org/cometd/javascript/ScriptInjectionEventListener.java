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

import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;

/**
 * A DOM listener that listens for injections of &lt;script&gt; tags
 * and calls a JavaScript function when it detects such injections.
 * This mechanism is used to simulate the browser behavior in case
 * of callback-polling transport.
 * This class is used from the {@code browser.js} script.
 */
public class ScriptInjectionEventListener implements EventListener {
    private final JavaScript javaScript;
    private final Object jsThis;
    private final Object function;
    // Maps Java DOM nodes to JS DOM nodes.
    private final Map<?, ?> domNodes;

    public ScriptInjectionEventListener(JavaScript javaScript, Object jsThis, Object function, Map<?, ?> domNodes) {
        this.javaScript = javaScript;
        this.jsThis = jsThis;
        this.function = function;
        this.domNodes = domNodes;
    }

    @Override
    public void handleEvent(Event evt) {
        if ("DOMNodeInserted".equals(evt.getType())) {
            Object target = evt.getTarget();
            if (target instanceof Element) {
                Element element = (Element)target;
                if ("script".equalsIgnoreCase(element.getNodeName())) {
                    javaScript.invoke(true, jsThis, function, domNodes.get(element));
                }
            }
        }
    }
}
