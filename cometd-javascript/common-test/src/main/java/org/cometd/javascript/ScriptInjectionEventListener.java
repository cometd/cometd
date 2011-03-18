package org.cometd.javascript;

import java.util.Map;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.w3c.dom.Element;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;

/**
 * A DOM listener that listen for injections of &lt;script&gt; tags
 * and calls a JavaScript function when it detects such injections.
 * This mechanism is used to simulate the browser behavior in case
 * of callback-polling transport.
 * This class is used from the env.js script.
 *
 * @version $Revision$ $Date$
 */
public class ScriptInjectionEventListener implements EventListener
{
    private final ThreadModel threadModel;
    private final Scriptable thiz;
    private final Function function;
    private final Map domNodes;

    public ScriptInjectionEventListener(ThreadModel threadModel, Scriptable thiz, Function function, Map domNodes)
    {
        this.threadModel = threadModel;
        this.thiz = thiz;
        this.function = function;
        this.domNodes = domNodes;
    }

    public void handleEvent(Event evt)
    {
        if ("DOMNodeInserted".equals(evt.getType()))
        {
            Object target = evt.getTarget();
            if (target instanceof Element)
            {
                Element element = (Element)target;
                if ("script".equalsIgnoreCase(element.getNodeName()))
                {
                    String src = element.getAttribute("src");
                    if (src != null && src.length() > 0)
                    {
                        threadModel.execute(thiz, thiz, function, domNodes.get(element));
                    }
                }
            }
        }
    }
}
