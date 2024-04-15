/*
 * Simulated browser environment for GraalJS.
 * Based on the work by John Resig <http://ejohn.org/> under the MIT License.
 */

// The window global object.
globalThis.window = {};

// Make Latch available to tests.
globalThis.Latch = Java.type("org.cometd.javascript.Latch");

// Objects used also on the Java side.
// The var keyword is necessary for these definitions,
// to guarantee interoperability with the Java side.
var cookies;
var xhrClient;
var wsConnector;
var sessionStorage;

function _equalsIgnoreCase(s1, s2) {
    if (s1 === s2) {
        return true;
    }
    if (s1 !== undefined && s1 !== null) {
        if (s2 !== undefined && s2 !== null) {
            return s1.toUpperCase() === s2.toUpperCase();
        }
    }
    return false;
}


// Browser Navigator
window.navigator = {
    get appVersion() {
        return "5.0 (X11; en-US)";
    },
    get userAgent() {
        return "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:49.0) Gecko/20100101 Firefox/49.0";
    },
    get language() {
        return "en-US";
    }
};


// Setup location properties
let _location;
Object.defineProperty(window, "location", {
    set(url) {
        const urlParts = /(^https?:)\/\/(([^:\/?#]+)(:(\d+))?)([^?#]*)?(\?[^#]*)?(#.*)?/.exec(url);
        _location = {
            href: url,
            protocol: urlParts[1],
            host: urlParts[2],
            hostname: urlParts[3],
            port: urlParts[4] ? urlParts[5] : "",
            pathname: urlParts[6] || "",
            search: urlParts[7] || "",
            hash: urlParts[8] || ""
        };
    },
    get() {
        return _location;
    }
});


// The output console
window.console = (() => {
    // Converts JavaScript objects to JSON.
    // We cannot use Crockford's JSON because it cannot handle
    // cyclic data structures properly, so we redo it here.
    function _toJSON(object, ids) {
        switch (typeof object) {
            case "string":
                return `"${object}"`;
            case "number":
                return "" + object;
            case "boolean":
                return "" + object;
            case "undefined":
                return undefined;
            case "object":
                if (!object) {
                    return "null";
                } else if (Array.isArray(object)) {
                    for (let aid = 0; aid < ids.length; ++aid) {
                        if (ids[aid] === object) {
                            return undefined;
                        }
                    }
                    ids.push(object);

                    let arrayResult = "[";
                    for (let i = 0; i < object.length; ++i) {
                        const arrayValue = _toJSON(object[i], ids);
                        if (arrayValue !== undefined) {
                            if (i > 0) {
                                arrayResult += ",";
                            }
                            arrayResult += arrayValue;
                        }
                    }
                    arrayResult += "]";
                    return arrayResult;
                } else {
                    for (let oid = 0; oid < ids.length; ++oid) {
                        if (ids[oid] === object) {
                            return undefined;
                        }
                    }
                    ids.push(object);

                    let objectResult = "{";
                    for (let name in object) {
                        if (objectResult.length > 1) {
                            objectResult += ",";
                        }
                        objectResult += `"${name}":`;
                        const objectValue = _toJSON(object[name], ids);
                        if (objectValue !== undefined) {
                            objectResult += "" + objectValue;
                        }
                    }
                    objectResult += "}";
                    return objectResult;
                }
            case "function":
                return object.name ? object.name + "()" : "anonymous()";
            default:
                throw "Unknown object type " + (typeof object);
        }
    }

    const _formatter = Java.type("java.time.format.DateTimeFormatter").ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    function _log(level, args) {
        let log = _formatter.format(Java.type("java.time.LocalDateTime").now());
        log += " " + Java.type("java.lang.Thread").currentThread().getName();
        log += " [" + level + "][browser.js]";
        for (let i = 0; i < args.length; ++i) {
            let element = args[i];
            if (typeof element === "object") {
                element = _toJSON(element, []);
            }
            log += " " + element;
        }
        Java.type("java.lang.System").err.println(log);
    }

    return {
        error() {
            _log("ERROR", arguments);
        },
        warn() {
            _log(" WARN", arguments);
        },
        info() {
            _log(" INFO", arguments);
        },
        debug() {
            _log("DEBUG", arguments);
        },
        log() {
            _log("  LOG", arguments);
        }
    };
})();


// Timers
window.setTimeout = (fn, delay) => {
    delay = delay || 0;
    return javaScript.schedule(window, fn, delay);
};
window.clearTimeout = handle => {
    if (handle) {
        handle.cancel(true);
    }
};
window.setInterval = (fn, period) => javaScript.scheduleWithFixedDelay(window, fn, period, period);
window.clearInterval = handle => {
    if (handle) {
        handle.cancel(true);
    }
};


// Window Events
const _events = [{}];
window.addEventListener = (type, fn) => {
    if (!this.uuid || this === window) {
        this.uuid = _events.length;
        _events[this.uuid] = {};
    }

    if (!_events[this.uuid][type]) {
        _events[this.uuid][type] = [];
    }

    if (_events[this.uuid][type].indexOf(fn) < 0) {
        _events[this.uuid][type].push(fn);
    }
};
window.removeEventListener = (type, fn) => {
    if (!this.uuid || this === window) {
        this.uuid = _events.length;
        _events[this.uuid] = {};
    }

    if (!_events[this.uuid][type]) {
        _events[this.uuid][type] = [];
    }

    _events[this.uuid][type] =
        _events[this.uuid][type].filter(f => f !== fn);
};
window.dispatchEvent = event => {
    if (event.type) {
        if (this.uuid && _events[this.uuid][event.type]) {
            _events[this.uuid][event.type].forEach(fn => {
                fn.call(this, event);
            });
        }

        if (this["on" + event.type]) {
            this["on" + event.type].call(this, event);
        }
    }
};

/**
 * Performs a GET request to retrieve the content of the given URL,
 * simulating the behavior of a browser calling the URL of the src
 * attribute of the script tag.
 *
 * @param script the script element injected
 */
function makeScriptRequest(script) {
    if (script.src) {
        const xhr = new window.XMLHttpRequest();
        xhr.open("GET", script.src, true);
        xhr.onload = () => {
            eval("window." + xhr.responseText);

            if (script.onload && typeof script.onload === "function") {
                script.onload.call(script);
            } else {
                const event = window.document.createEvent();
                event.initEvent("load", true, true);
                script.dispatchEvent(event);
            }
        };
        xhr.send();
    } else if (script.text) {
        eval(script.text);
    }
}

const _domNodes = new (Java.type("java.util.HashMap"))();

/**
 * Helper method for generating the right javascript DOM objects based upon the node type.
 * If the javascript node exists, returns it, otherwise creates a corresponding javascript node.
 * @param javaNode the java node to convert to javascript node
 */
function makeNode(javaNode) {
    if (!javaNode) {
        return null;
    }
    if (_domNodes.containsKey(javaNode)) {
        return _domNodes.get(javaNode);
    }
    const isElement = javaNode.getNodeType() === org.w3c.dom.Node.ELEMENT_NODE;
    const jsNode = isElement ? new window.DOMElement(javaNode) : new window.DOMNode(javaNode);
    _domNodes.put(javaNode, jsNode);
    return jsNode;
}

function makeHTMLDocument(html) {
    const utf8 = Java.type("java.nio.charset.StandardCharsets").UTF_8.encode(html);
    const bytes = [];
    while (utf8.hasRemaining()) {
        bytes.push(utf8.get());
    }
    return new window.DOMDocument(new (Java.type("java.io.ByteArrayInputStream"))(bytes));
}


// DOM Node
window.DOMNode = class {
    constructor(node) {
        this._dom = node;
    }

    // START OFFICIAL DOM
    get nodeName() {
        return this._dom.getNodeName();
    }

    get nodeValue() {
        return this._dom.getNodeValue();
    }

    get nodeType() {
        return this._dom.getNodeType();
    }

    get parentNode() {
        return makeNode(this._dom.getParentNode());
    }

    get childNodes() {
        return new window.DOMNodeList(this._dom.getChildNodes());
    }

    get firstChild() {
        return makeNode(this._dom.getFirstChild());
    }

    get lastChild() {
        return makeNode(this._dom.getLastChild());
    }

    get previousSibling() {
        return makeNode(this._dom.getPreviousSibling());
    }

    get nextSibling() {
        return makeNode(this._dom.getNextSibling());
    }

    get attributes() {
        const jsAttributes = {};
        const javaAttributes = this._dom.getAttributes();
        if (javaAttributes) {
            for (let i = 0; i < javaAttributes.getLength(); ++i) {
                const javaAttribute = javaAttributes.item(i);
                jsAttributes[javaAttribute.nodeName] = javaAttribute.nodeValue;
            }
        }
        return jsAttributes;
    }

    get ownerDocument() {
        return makeNode(this._dom.getOwnerDocument());
    }

    insertBefore(node, before) {
        return makeNode(this._dom.insertBefore(node._dom, before ? before._dom : before));
    }

    replaceChild(newNode, oldNode) {
        return makeNode(this._dom.replaceChild(newNode._dom, oldNode._dom));
    }

    removeChild(node) {
        return makeNode(this._dom.removeChild(node._dom));
    }

    appendChild(node) {
        return makeNode(this._dom.appendChild(node._dom));
    }

    hasChildNodes() {
        return this._dom.hasChildNodes();
    }

    cloneNode(deep) {
        return makeNode(this._dom.cloneNode(deep));
    }

    normalize() {
        this._dom.normalize();
    }

    isSupported(feature, version) {
        return this._dom.isSupported(feature, version);
    }

    get namespaceURI() {
        return this._dom.getNamespaceURI();
    }

    get prefix() {
        return this._dom.getPrefix();
    }

    set prefix(value) {
        this._dom.setPrefix(value);
    }

    get localName() {
        return this._dom.getLocalName();
    }

    hasAttributes() {
        return this._dom.hasAttributes();
    }

    // END OFFICIAL DOM

    addEventListener(type, fn) {
        window.addEventListener(type, fn);
    }

    removeEventListener(type, fn) {
        window.removeEventListener(type, fn);
    }

    dispatchEvent(event) {
        window.dispatchEvent(event);
    }

    get documentElement() {
        return makeNode(this._dom.documentElement);
    }

    get outerHTML() {
        return this.nodeValue;
    }

    toString() {
        return `"${this.nodeValue}"`;
    }
}


// DOM Implementation
window.DOMImplementation = class {
    createHTMLDocument(title) {
        return makeHTMLDocument(`<html lang="en"><head><title>${title}</title></head><body></body></html>`);
    }
}


// DOM Document
const ScriptInjectionEventListener = Java.type("org.cometd.javascript.ScriptInjectionEventListener");

window.DOMDocument = class extends window.DOMNode {
    constructor(stream) {
        super(Java.type("javax.xml.parsers.DocumentBuilderFactory").newInstance().newDocumentBuilder().parse(stream));
        this._file = stream;

        if (!_domNodes.containsKey(this._dom)) {
            _domNodes.put(this._dom, this);
        }

        const listener = new ScriptInjectionEventListener(javaScript, window, makeScriptRequest, _domNodes);
        this._dom.addEventListener("DOMNodeInserted", listener, false);

        this._impl = new window.DOMImplementation();
    }

    // START OFFICIAL DOM
//        doctype
    get implementation() {
        return this._impl;
    }

    get documentElement() {
        return makeNode(this._dom.getDocumentElement());
    }

    createElement(name) {
        return makeNode(this._dom.createElement(name.toLowerCase()));
    }

    createDocumentFragment() {
        return makeNode(this._dom.createDocumentFragment());
    }

    createTextNode(text) {
        return makeNode(this._dom.createTextNode(text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")));
    }

    createComment(text) {
        return makeNode(this._dom.createComment(text));
    }

//        createCDATASection
//        createProcessingInstruction
//        createAttribute
//        createEntityReference
    getElementsByTagName(name) {
        return new window.DOMNodeList(this._dom.getElementsByTagName(name.toLowerCase()));
    }

    importNode(node, deep) {
        return makeNode(this._dom.importNode(node._dom, deep));
    }

//        createElementNS
//        createAttributeNS
//        getElementsByTagNameNS
    getElementById(id) {
        const elems = this._dom.getElementsByTagName("*");

        for (let i = 0; i < elems.length; i++) {
            const elem = elems.item(i);
            if (elem.getAttribute("id") === id) {
                return makeNode(elem);
            }
        }

        return null;
    }

    // END OFFICIAL DOM

    get body() {
        return this.getElementsByTagName("body")[0];
    }

    get ownerDocument() {
        return null;
    }

    get nodeName() {
        return "#document";
    }

    toString() {
        return "Document" + (typeof this._file === "string" ?
            ": " + this._file : "");
    }

    get innerHTML() {
        return this.documentElement.outerHTML;
    }

    get defaultView() {
        return {
            getComputedStyle: elem => ({
                getPropertyValue(prop) {
                    prop = prop.replace(/-(\w)/g, (m, c) => c.toUpperCase());
                    let val = elem.style[prop];
                    if (_equalsIgnoreCase(prop, "opacity") && val === "") {
                        val = "1";
                    }
                    return val;
                }
            })
        };
    }

    createEvent() {
        return {
            type: "",
            initEvent(type) {
                this.type = type;
            }
        };
    }

    get cookie() {
        return cookies.get(window.location.protocol, window.location.host, window.location.pathname);
    }

    set cookie(value) {
        cookies.set(window.location.protocol, window.location.host, window.location.pathname, value);
    }

    get location() {
        return window.location;
    }
}


// DOM NodeList
window.DOMNodeList = class {
    constructor(list) {
        this._dom = list;
        this.length = list.getLength();

        for (let i = 0; i < this.length; i++) {
            const node = list.item(i);
            this[i] = makeNode(node);
        }
    }

    toString() {
        return "[ " +
            Array.prototype.join.call(this, ", ") + " ]";
    }

    get outerHTML() {
        return Array.prototype.map.call(this, node => node.outerHTML).join("");
    }
}


// DOM Element
window.DOMElement = class extends window.DOMNode {
    _style;
    _opacity;

    constructor(elem) {
        super(elem);
        this._style = {
            get opacity() {
                return this._opacity;
            },
            set opacity(val) {
                this._opacity = val + "";
            }
        }

        // Load CSS info
        const styles = (this.getAttribute("style") || "").split(/\s*;\s*/);

        for (let i = 0; i < styles.length; i++) {
            const style = styles[i].split(/\s*:\s*/);
            if (style.length === 2) {
                this.style[style[0]] = style[1];
            }
        }
    }

    // START OFFICIAL DOM
    get tagName() {
        return this._dom.getTagName();
    }

    getAttribute(name) {
        return this._dom.hasAttribute(name) ? this._dom.getAttribute(name) : null;
    }

    setAttribute(name, value) {
        this._dom.setAttribute(name, value ? value.toString() : null);
    }

    removeAttribute(name) {
        this._dom.removeAttribute(name);
    }

//        getAttributeNode
//        setAttributeNode
//        removeAttributeNode
    getElementsByTagName(name) {
        return new window.DOMNodeList(this._dom.getElementsByTagName(name.toLowerCase()));
    }

//        getAttributeNS
//        setAttributeNS
//        removeAttributeNS
//        getAttributeNodeNS
//        setAttributeNodeNS
//        getElementsByTagNameNS
    hasAttribute(name) {
        return this._dom.hasAttribute(name);
    }

//        hasAttributeNS
    // END OFFICIAL DOM

    get nodeName() {
        return this.tagName.toUpperCase();
    }

    toString() {
        return "<" + this.tagName + (this.id ? "#" + this.id : "") + ">";
    }

    get outerHTML() {
        let ret = "<" + this.tagName;
        const attr = this.attributes;

        for (let i in attr) {
            if (attr.hasOwnProperty(i)) {
                ret += ` ${i}="${attr[i]}"`;
            }
        }

        if (this.childNodes.length || _equalsIgnoreCase(this.nodeName, "script")) {
            ret += `>${this.childNodes.outerHTML}</${this.tagName}>`;
        } else {
            ret += "/>";
        }

        return ret;
    }

    get innerHTML() {
        return this.childNodes.outerHTML;
    }

    set innerHTML(html) {
        html = html.replace(/<\/?([A-Z]+)/g, m => m.toLowerCase());

        const innerDoc = makeHTMLDocument(`<wrap>${html}</wrap>`);
        const nodes = this.ownerDocument.importNode(innerDoc.documentElement, true).childNodes;

        while (this.firstChild) {
            this.removeChild(this.firstChild);
        }

        for (let i = 0; i < nodes.length; i++) {
            this.appendChild(nodes[i]);
        }
    }

    get textContent() {
        return nav(this.childNodes);

        function nav(nodes) {
            let str = "";
            for (let i = 0; i < nodes.length; i++) {
                if (nodes[i].nodeType === 3) {
                    str += nodes[i].nodeValue;
                } else if (nodes[i].nodeType === 1) {
                    str += nav(nodes[i].childNodes);
                }
            }
            return str;
        }
    }

    set textContent(text) {
        while (this.firstChild) {
            this.removeChild(this.firstChild);
        }
        this.appendChild(this.ownerDocument.createTextNode(text));
    }

    get style() {
        return this._style;
    }

    get clientHeight() {
        return 0;
    }

    get clientWidth() {
        return 0;
    }

    get offsetHeight() {
        return 0;
    }

    get offsetWidth() {
        return 0;
    }

    get disabled() {
        const val = this.getAttribute("disabled");
        return val !== "false" && !!val;
    }

    set disabled(val) {
        return this.setAttribute("disabled", val);
    }

    get checked() {
        const val = this.getAttribute("checked");
        return val !== "false" && !!val;
    }

    set checked(val) {
        return this.setAttribute("checked", val);
    }

    get selected() {
        if (!this._selectDone) {
            this._selectDone = true;

            if (_equalsIgnoreCase(this.nodeName, "option") && !this.parentNode.getAttribute("multiple")) {
                const opt = this.parentNode.getElementsByTagName("option");

                if (this === opt[0]) {
                    let select = true;

                    for (let i = 1; i < opt.length; i++) {
                        if (opt[i].selected) {
                            select = false;
                            break;
                        }
                    }

                    if (select) {
                        this.selected = true;
                    }
                }
            }
        }
        const val = this.getAttribute("selected");
        return val !== "false" && !!val;
    }

    set selected(val) {
        return this.setAttribute("selected", val);
    }

    get className() {
        return this.getAttribute("class") || "";
    }

    set className(val) {
        return this.setAttribute("class", val.replace(/(^\s*|\s*$)/g, ""));
    }

    get type() {
        return this.getAttribute("type") || "";
    }

    set type(val) {
        return this.setAttribute("type", val);
    }

    get value() {
        return this.getAttribute("value") || "";
    }

    set value(val) {
        return this.setAttribute("value", val);
    }

    get src() {
        return this.getAttribute("src") || "";
    }

    set src(val) {
        return this.setAttribute("src", val);
    }

    get id() {
        return this.getAttribute("id") || "";
    }

    set id(val) {
        return this.setAttribute("id", val);
    }

    click() {
        const event = document.createEvent();
        event.initEvent("click");
        this.dispatchEvent(event);
    }

    submit() {
        const event = document.createEvent();
        event.initEvent("submit");
        this.dispatchEvent(event);
    }

    focus() {
        const event = document.createEvent();
        event.initEvent("focus");
        this.dispatchEvent(event);
    }

    blur() {
        const event = document.createEvent();
        event.initEvent("blur");
        this.dispatchEvent(event);
    }

    get elements() {
        return this.getElementsByTagName("*");
    }

    get contentWindow() {
        return _equalsIgnoreCase(this.nodeName, "iframe") ? {
            document: this.contentDocument
        } : null;
    }

    get contentDocument() {
        if (_equalsIgnoreCase(this.nodeName, "iframe")) {
            if (!this._doc) {
                this._doc = makeHTMLDocument(`<html lang="en"><head><title></title></head><body></body></html>`);
            }
            return this._doc;
        } else {
            return null;
        }
    }
}


// Fake document object.
window.document = makeHTMLDocument(`<html lang="en"><head><title></title><script></script></head><body></body></html>`);
window.document.head = window.document.getElementsByTagName("head")[0];
window.screen = {};
window.innerWidth = 0;
window.assert = (condition, text) => {
    if (!condition) {
        throw "ASSERTION FAILED" + (text ? ": " + text : "");
    }
};


// Using an implementation of XMLHttpRequest that uses java.net.URL is asking for troubles
// since socket connections are pooled but there is no control on how they're used, when
// they are closed and how many of them are opened.
// When using java.net.URL it happens that a long poll can be closed at any time,
// just to be reissued using another socket.
// Therefore, we use helper classes that are based on Jetty's HttpClient, which offers full control.
const XMLHttpRequestExchange = Java.type("org.cometd.javascript.XMLHttpRequestExchange");

window.XMLHttpRequest = class {
    static UNSENT = 0;
    static OPENED = 1;
    static HEADERS_RECEIVED = 2;
    static LOADING = 3;
    static DONE = 4;

    #exchange;
    #withCredentials;

    get readyState() {
        const exchange = this.#exchange;
        return exchange ? exchange.getReadyState() : window.XMLHttpRequest.UNSENT;
    }

    get responseText() {
        const exchange = this.#exchange;
        return exchange ? exchange.getResponseText() : null;
    }

    get responseXML() {
        return null;
    }

    get status() {
        const exchange = this.#exchange;
        return exchange ? exchange.getResponseStatus() : 0;
    }

    get statusText() {
        const exchange = this.#exchange;
        return exchange ? exchange.getResponseStatusText() : null;
    }

    onreadystatechange() {
        // This function may be polled with a timer, do not throw if
        // this function is called (like we do with WebSocket below).
    }

    onload() {
    }

    onerror() {
    }

    onabort() {
    }

    open(method, url, async) {
        const absolute = /^https?:\/\//.test(url);
        const absoluteURL = absolute ? url : window.location.href + url;
        this.#exchange = new XMLHttpRequestExchange(xhrClient, javaScript, this, method, absoluteURL, async);
    }

    setRequestHeader(header, value) {
        const ready = this.readyState;
        if (ready !== window.XMLHttpRequest.OPENED) {
            throw "INVALID_STATE_ERR: " + ready;
        }
        if (!header) {
            throw "SYNTAX_ERR";
        }
        if (value) {
            this.#exchange.addRequestHeader(header, value);
        }
    }

    send(data) {
        const ready = this.readyState;
        if (ready !== window.XMLHttpRequest.OPENED) {
            throw "INVALID_STATE_ERR";
        }
        const exchange = this.#exchange;
        if (exchange.getMethod() === "GET") {
            data = null;
        }
        if (data) {
            exchange.setRequestContent(data);
        }
        exchange.send();
    }

    abort() {
        const exchange = this.#exchange;
        if (exchange) {
            exchange.abort();
        }
    }

    getAllResponseHeaders() {
        const ready = this.readyState;
        if (ready === window.XMLHttpRequest.UNSENT || ready === window.XMLHttpRequest.OPENED) {
            throw "INVALID_STATE_ERR";
        }
        return this.#exchange.getAllResponseHeaders();
    }

    getResponseHeader(header) {
        const ready = this.readyState;
        if (ready === window.XMLHttpRequest.UNSENT || ready === window.XMLHttpRequest.OPENED) {
            throw "INVALID_STATE_ERR";
        }
        return this.#exchange.getResponseHeader(header);
    }

    get withCredentials() {
        return !!this.#withCredentials;
    }

    set withCredentials(val) {
        this.#withCredentials = val;
    }
}

const WebSocketConnection = Java.type("org.cometd.javascript.WebSocketConnection");

window.WebSocket = class {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;

    #url;
    #ws;

    constructor(url, protocol) {
        this.#url = url;
        this.#ws = new WebSocketConnection(javaScript, this, wsConnector, url, protocol ? protocol : null);
    }

    get url() {
        return this.#url;
    }

    onopen() {
        window.assert(false, "onopen not assigned");
    }

    onerror() {
        window.assert(false, "onerror not assigned");
    }

    onclose() {
        window.assert(false, "onclose not assigned");
    }

    onmessage() {
        window.assert(false, "onmessage not assigned");
    }

    send(data) {
        this.#ws.send(data);
    }

    close(code, reason) {
        this.#ws.close(code, reason);
    }
}

// The session storage implementation is provided externally from Java.
window.sessionStorage = sessionStorage;

