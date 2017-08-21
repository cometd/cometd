/*
 * Simulated browser environment for Nashorn
 * Based on the work by by John Resig <http://ejohn.org/> under the MIT License.
 */

// The window global object.
var window = this;

// Objects used also on the Java side.
var cookies = new org.cometd.javascript.JavaScriptCookieStore();
var xhrClient = new org.cometd.javascript.XMLHttpRequestClient(cookies);
var wsConnector = new org.cometd.javascript.WebSocketConnector(cookies);
var sessionStorage = new org.cometd.javascript.SessionStorage();

// TODO: to be removed ?
var Latch = Java.type('org.cometd.javascript.Latch');

(function() {
    // Browser Navigator
    window.navigator = {
        get appVersion() {
            return '5.0 (X11; en-US)';
        },
        get userAgent() {
            return 'Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:49.0) Gecko/20100101 Firefox/49.0';
        },
        get language() {
            return 'en-US';
        }
    };


    // Setup location properties
    var _location;
    Object.defineProperty(window, 'location', {
        set: function(url) {
            var urlParts = /(^https?:)\/\/(([^:\/\?#]+)(:(\d+))?)([^\?#]*)?(\?[^#]*)?(#.*)?/.exec(url);
            _location = {
                href: url,
                protocol: urlParts[1],
                host: urlParts[2],
                hostname: urlParts[3],
                port: urlParts[4] ? urlParts[5] : '',
                pathname: urlParts[6] || '',
                search: urlParts[7] || '',
                hash: urlParts[8] || ''
            };
        },
        get: function() {
            return _location;
        }
    });


    // The output console
    window.console = function() {
        // Converts JavaScript objects to JSON.
        // We cannot use Crockford's JSON because it cannot handle
        // cyclic data structures properly, so we redo it here.
        function _toJSON(object, ids) {
            switch (typeof object) {
                case 'string':
                    return '"' + object + '"';
                case 'number':
                    return '' + object;
                case 'boolean':
                    return '' + object;
                case 'undefined':
                    return undefined;
                case 'object':
                    if (!object) {
                        return 'null';
                    } else if (Array.isArray(object)) {
                        for (var aid = 0; aid < ids.length; ++aid) {
                            if (ids[aid] === object) {
                                return undefined;
                            }
                        }
                        ids.push(object);

                        var arrayResult = '[';
                        for (var i = 0; i < object.length; ++i) {
                            var arrayValue = _toJSON(object[i], ids);
                            if (arrayValue !== undefined) {
                                if (i > 0) {
                                    arrayResult += ',';
                                }
                                arrayResult += arrayValue;
                            }
                        }
                        arrayResult += ']';
                        return arrayResult;
                    } else {
                        for (var oid = 0; oid < ids.length; ++oid) {
                            if (ids[oid] === object) {
                                return undefined;
                            }
                        }
                        ids.push(object);

                        var objectResult = '{';
                        for (var name in object) {
                            if (object.hasOwnProperty(name)) {
                                if (objectResult.length > 1) {
                                    objectResult += ',';
                                }
                                objectResult += '"' + name + '":';
                                var objectValue = _toJSON(object[name], ids);
                                if (objectValue !== undefined) {
                                    objectResult += '' + objectValue;
                                }
                            }
                        }
                        objectResult += '}';
                        return objectResult;
                    }
                case 'function':
                    return object.name ? object.name + '()' : 'anonymous()';
                default:
                    throw 'Unknown object type ' + (typeof object);
            }
        }

        function _log(level, args) {
            var formatter = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss.SSS');
            var log = formatter.format(new java.util.Date());
            log += ' ' + java.lang.Thread.currentThread().getName();
            log += ' [' + level + '][browser.js]';
            for (var i = 0; i < args.length; ++i) {
                var element = args[i];
                if (typeof element === 'object') {
                    element = _toJSON(element, []);
                }
                log += ' ' + element;
            }
            java.lang.System.err.println(log);
        }

        return {
            error: function() {
                _log('ERROR', arguments);
            },
            warn: function() {
                _log('WARN', arguments);
            },
            info: function() {
                _log('INFO', arguments);
            },
            debug: function() {
                _log('DEBUG', arguments);
            },
            log: function() {
                _log('LOG', arguments);
            }
        };
    }();


    // Timers
    window.setTimeout = function(fn, delay) {
        delay = delay || 0;
        return _scheduler.schedule(new java.lang.Runnable({
            run: function() {
                javaScript.invoke(true, window, fn);
            }
        }), delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    };
    window.clearTimeout = function(handle) {
        if (handle) {
            handle.cancel(true);
        }
    };
    window.setInterval = function(fn, period) {
        return _scheduler.scheduleWithFixedDelay(new java.lang.Runnable({
            run: function() {
                javaScript.invoke(true, window, fn);
            }
        }), period, period, java.util.concurrent.TimeUnit.MILLISECONDS);
    };
    window.clearInterval = function(handle) {
        handle.cancel(true);
    };


    // Window Events
    var _events = [{}];
    window.addEventListener = function(type, fn) {
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
    window.removeEventListener = function(type, fn) {
        if (!this.uuid || this === window) {
            this.uuid = _events.length;
            _events[this.uuid] = {};
        }

        if (!_events[this.uuid][type]) {
            _events[this.uuid][type] = [];
        }

        _events[this.uuid][type] =
            _events[this.uuid][type].filter(function(f) {
                return f !== fn;
            });
    };
    window.dispatchEvent = function(event) {
        if (event.type) {
            var self = this;

            if (this.uuid && _events[this.uuid][event.type]) {
                _events[this.uuid][event.type].forEach(function(fn) {
                    fn.call(self, event);
                });
            }

            if (this["on" + event.type]) {
                this["on" + event.type].call(self, event);
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
            var xhr = new window.XMLHttpRequest();
            xhr.open("GET", script.src, true);
            xhr.onload = function() {
                eval(this.responseText);

                if (script.onload && typeof script.onload === 'function') {
                    script.onload.call(script);
                } else {
                    var event = window.document.createEvent();
                    event.initEvent('load', true, true);
                    script.dispatchEvent(event);
                }
            };
            xhr.send();
        } else if (script.text) {
            eval(script.text);
        }
    }

    var _domNodes = new java.util.HashMap();

    /**
     * Helper method for generating the right javascript DOM objects based upon the node type.
     * If the java node exists, returns it, otherwise creates a corresponding javascript node.
     * @param javaNode the java node to convert to javascript node
     */
    function makeNode(javaNode) {
        if (!javaNode) {
            return null;
        }
        if (_domNodes.containsKey(javaNode)) {
            return _domNodes.get(javaNode);
        }
        var isElement = javaNode.getNodeType() === org.w3c.dom.Node.ELEMENT_NODE;
        var jsNode = isElement ? new window.DOMElement(javaNode) : new window.DOMNode(javaNode);
        _domNodes.put(javaNode, jsNode);
        return jsNode;
    }

    function makeHTMLDocument(html) {
        var bytes = (new Packages.java.lang.String(html)).getBytes("UTF8");
        return new window.DOMDocument(new Packages.java.io.ByteArrayInputStream(bytes));
    }


    // DOM Node
    window.DOMNode = function(node) {
        this._dom = node;
    };
    window.DOMNode.prototype = {
        // START OFFICIAL DOM
        get nodeName() {
            return this._dom.getNodeName();
        },
        get nodeValue() {
            return this._dom.getNodeValue();
        },
        get nodeType() {
            return this._dom.getNodeType();
        },
        get parentNode() {
            return makeNode(this._dom.getParentNode());
        },
        get childNodes() {
            return new window.DOMNodeList(this._dom.getChildNodes());
        },
        get firstChild() {
            return makeNode(this._dom.getFirstChild());
        },
        get lastChild() {
            return makeNode(this._dom.getLastChild());
        },
        get previousSibling() {
            return makeNode(this._dom.getPreviousSibling());
        },
        get nextSibling() {
            return makeNode(this._dom.getNextSibling());
        },
        get attributes() {
            var jsAttributes = {};
            var javaAttributes = this._dom.getAttributes();
            for (var i = 0; i < javaAttributes.getLength(); ++i) {
                var javaAttribute = javaAttributes.item(i);
                jsAttributes[javaAttribute.nodeName] = javaAttribute.nodeValue;
            }
            return jsAttributes;
        },
        get ownerDocument() {
            return _domNodes.get(this._dom.ownerDocument);
        },
        insertBefore: function(node, before) {
            return makeNode(this._dom.insertBefore(node._dom, before ? before._dom : before));
        },
        replaceChild: function(newNode, oldNode) {
            return makeNode(this._dom.replaceChild(newNode._dom, oldNode._dom));
        },
        removeChild: function(node) {
            return makeNode(this._dom.removeChild(node._dom));
        },
        appendChild: function(node) {
            return makeNode(this._dom.appendChild(node._dom));
        },
        hasChildNodes: function() {
            return this._dom.hasChildNodes();
        },
        cloneNode: function(deep) {
            return makeNode(this._dom.cloneNode(deep));
        },
        normalize: function() {
            this._dom.normalize();
        },
        isSupported: function(feature, version) {
            return this._dom.isSupported(feature, version);
        },
        get namespaceURI() {
            return this._dom.getNamespaceURI();
        },
        get prefix() {
            return this._dom.getPrefix();
        },
        set prefix(value) {
            this._dom.setPrefix(value);
        },
        get localName() {
            return this._dom.getLocalName();
        },
        hasAttributes: function() {
            return this._dom.hasAttributes();
        },
        // END OFFICIAL DOM

        addEventListener: window.addEventListener,
        removeEventListener: window.removeEventListener,
        dispatchEvent: window.dispatchEvent,

        get documentElement() {
            return makeNode(this._dom.documentElement);
        },
        toString: function() {
            return '"' + this.nodeValue + '"';
        },
        get outerHTML() {
            return this.nodeValue;
        }
    };


    // DOM Implementation
    window.DOMImplementation = function() {
    };
    window.DOMImplementation.prototype = {
        createHTMLDocument: function(title) {
            return makeHTMLDocument("<html><head><title>" + title + "</title></head><body></body></html>");
        }
    };

    // DOM Document
    var ScriptInjectionEventListener = Java.type('org.cometd.javascript.ScriptInjectionEventListener');
    window.DOMDocument = function(stream) {
        this._file = stream;
        this._dom = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);

        if (!_domNodes.containsKey(this._dom)) {
            _domNodes.put(this._dom, this);
        }

        var listener = new ScriptInjectionEventListener(javaScript, window, makeScriptRequest, _domNodes);
        this._dom.addEventListener('DOMNodeInserted', listener, false);

        this._impl = new window.DOMImplementation();
    };
    window.DOMDocument.prototype = extend(new DOMNode(), {
        // START OFFICIAL DOM
//        doctype
        get implementation() {
           return this._impl;
        },
        get documentElement() {
            return makeNode(this._dom.getDocumentElement());
        },
        createElement: function(name) {
            return makeNode(this._dom.createElement(name.toLowerCase()));
        },
        createDocumentFragment: function() {
            return makeNode(this._dom.createDocumentFragment());
        },
        createTextNode: function(text) {
            return makeNode(this._dom.createTextNode(text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")));
        },
        createComment: function(text) {
            return makeNode(this._dom.createComment(text));
        },
//        createCDATASection
//        createProcessingInstruction
//        createAttribute
//        createEntityReference
        getElementsByTagName: function(name) {
            return new window.DOMNodeList(this._dom.getElementsByTagName(
                name.toLowerCase()));
        },
        importNode: function(node, deep) {
            return makeNode(this._dom.importNode(node._dom, deep));
        },
//        createElementNS
//        createAttributeNS
//        getElementsByTagNameNS
        getElementById: function(id) {
            var elems = this._dom.getElementsByTagName("*");

            for (var i = 0; i < elems.length; i++) {
                var elem = elems.item(i);
                if (elem.getAttribute("id") === id) {
                    return makeNode(elem);
                }
            }

            return null;
        },
        // END OFFICIAL DOM

        get body() {
            return this.getElementsByTagName("body")[0];
        },
        get ownerDocument() {
            return null;
        },
        get nodeName() {
            return "#document";
        },
        toString: function() {
            return "Document" + (typeof this._file === "string" ?
                ": " + this._file : "");
        },
        get innerHTML() {
            return this.documentElement.outerHTML;
        },
        get defaultView() {
            return {
                getComputedStyle: function(elem) {
                    return {
                        getPropertyValue: function(prop) {
                            prop = prop.replace(/\-(\w)/g, function(m, c) {
                                return c.toUpperCase();
                            });
                            var val = elem.style[prop];

                            if (prop === "opacity" && val === "") {
                                val = "1";
                            }

                            return val;
                        }
                    };
                }
            };
        },
        createEvent: function() {
            return {
                type: "",
                initEvent: function(type) {
                    this.type = type;
                }
            };
        },
        get cookie() {
            return cookies.get(window.location.protocol, window.location.host, window.location.pathname);
        },
        set cookie(value) {
            cookies.set(window.location.protocol, window.location.host, window.location.pathname, value);
        },
        get location() {
            return window.location;
        }
    });


    // DOM NodeList
    window.DOMNodeList = function(list) {
        this._dom = list;
        this.length = list.getLength();

        for (var i = 0; i < this.length; i++) {
            var node = list.item(i);
            this[i] = makeNode(node);
        }
    };
    window.DOMNodeList.prototype = {
        toString: function() {
            return "[ " +
                Array.prototype.join.call(this, ", ") + " ]";
        },
        get outerHTML() {
            return Array.prototype.map.call(
                this, function(node) {
                    return node.outerHTML;
                }).join('');
        }
    };


    // DOM Element
    window.DOMElement = function(elem) {
        this._dom = elem;
        this.style = {
            get opacity() {
                return this._opacity;
            },
            set opacity(val) {
                this._opacity = val + "";
            }
        };

        // Load CSS info
        var styles = (this.getAttribute("style") || "").split(/\s*;\s*/);

        for (var i = 0; i < styles.length; i++) {
            var style = styles[i].split(/\s*:\s*/);
            if (style.length === 2) {
                this.style[style[0]] = style[1];
            }
        }
    };
    window.DOMElement.prototype = extend(new DOMNode(), {
        // START OFFICIAL DOM
        get tagName() {
            return this._dom.getTagName();
        },
        getAttribute: function(name) {
            return this._dom.hasAttribute(name) ? this._dom.getAttribute(name) : null;
        },
        setAttribute: function(name, value) {
            this._dom.setAttribute(name, value);
        },
        removeAttribute: function(name) {
            this._dom.removeAttribute(name);
        },
//        getAttributeNode
//        setAttributeNode
//        removeAttributeNode
        getElementsByTagName: DOMDocument.prototype.getElementsByTagName,
//        getAttributeNS
//        setAttributeNS
//        removeAttributeNS
//        getAttributeNodeNS
//        setAttributeNodeNS
//        getElementsByTagNameNS
        hasAttribute: function(name) {
            return this._dom.hasAttribute(name);
        },
//        hasAttributeNS
        // END OFFICIAL DOM

        get nodeName() {
            return this.tagName.toUpperCase();
        },
        toString: function() {
            return "<" + this.tagName + (this.id ? "#" + this.id : "" ) + ">";
        },
        get outerHTML() {
            var ret = "<" + this.tagName, attr = this.attributes;

            for (var i in attr) {
                if (attr.hasOwnProperty(i)) {
                    ret += " " + i + "='" + attr[i] + "'";
                }
            }

            if (this.childNodes.length || this.nodeName === "SCRIPT") {
                ret += ">" + this.childNodes.outerHTML +
                    "</" + this.tagName + ">";
            } else {
                ret += "/>";
            }

            return ret;
        },
        get innerHTML() {
            return this.childNodes.outerHTML;
        },
        set innerHTML(html) {
            html = html.replace(/<\/?([A-Z]+)/g, function(m) {
                return m.toLowerCase();
            });

            var nodes = this.ownerDocument.importNode(
                new window.DOMDocument(new java.io.ByteArrayInputStream(
                    (new java.lang.String("<wrap>" + html + "</wrap>"))
                        .getBytes("UTF8"))).documentElement, true).childNodes;

            while (this.firstChild) {
                this.removeChild(this.firstChild);
            }

            for (var i = 0; i < nodes.length; i++) {
                this.appendChild(nodes[i]);
            }
        },
        get textContent() {
            return nav(this.childNodes);

            function nav(nodes) {
                var str = "";
                for (var i = 0; i < nodes.length; i++) {
                    if (nodes[i].nodeType === 3) {
                        str += nodes[i].nodeValue;
                    } else if (nodes[i].nodeType === 1) {
                        str += nav(nodes[i].childNodes);
                    }
                }
                return str;
            }
        },
        set textContent(text) {
            while (this.firstChild) {
                this.removeChild(this.firstChild);
            }
            this.appendChild(this.ownerDocument.createTextNode(text));
        },
        style: {},
        clientHeight: 0,
        clientWidth: 0,
        offsetHeight: 0,
        offsetWidth: 0,
        get disabled() {
            var val = this.getAttribute("disabled");
            return val !== "false" && !!val;
        },
        set disabled(val) {
            return this.setAttribute("disabled", val);
        },
        get checked() {
            var val = this.getAttribute("checked");
            return val !== "false" && !!val;
        },
        set checked(val) {
            return this.setAttribute("checked", val);
        },
        get selected() {
            if (!this._selectDone) {
                this._selectDone = true;

                if (this.nodeName === "OPTION" && !this.parentNode.getAttribute("multiple")) {
                    var opt = this.parentNode.getElementsByTagName("option");

                    if (this === opt[0]) {
                        var select = true;

                        for (var i = 1; i < opt.length; i++) {
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
            var val = this.getAttribute("selected");
            return val !== "false" && !!val;
        },
        set selected(val) {
            return this.setAttribute("selected", val);
        },
        get className() {
            return this.getAttribute("class") || "";
        },
        set className(val) {
            return this.setAttribute("class", val.replace(/(^\s*|\s*$)/g, ""));
        },
        get type() {
            return this.getAttribute("type") || "";
        },
        set type(val) {
            return this.setAttribute("type", val);
        },
        get value() {
            return this.getAttribute("value") || "";
        },
        set value(val) {
            return this.setAttribute("value", val);
        },
        get src() {
            return this.getAttribute("src") || "";
        },
        set src(val) {
            return this.setAttribute("src", val);
        },
        get id() {
            return this.getAttribute("id") || "";
        },
        set id(val) {
            return this.setAttribute("id", val);
        },
        click: function() {
            var event = document.createEvent();
            event.initEvent("click");
            this.dispatchEvent(event);
        },
        submit: function() {
            var event = document.createEvent();
            event.initEvent("submit");
            this.dispatchEvent(event);
        },
        focus: function() {
            var event = document.createEvent();
            event.initEvent("focus");
            this.dispatchEvent(event);
        },
        blur: function() {
            var event = document.createEvent();
            event.initEvent("blur");
            this.dispatchEvent(event);
        },
        get elements() {
            return this.getElementsByTagName("*");
        },
        get contentWindow() {
            return this.nodeName === "IFRAME" ? {
                document: this.contentDocument
            } : null;
        },
        get contentDocument() {
            if (this.nodeName === "IFRAME") {
                if (!this._doc) {
                    this._doc = makeHTMLDocument("<html><head></head><body></body></html>");
                }
                return this._doc;
            } else {
                return null;
            }
        }
    });


    // Fake document object. Dojo needs a script element to work properly.
    window.document = makeHTMLDocument("<html><head><title></title><script></script></head><body></body></html>");
    window.document.head = window.document.getElementsByTagName('head')[0];


    // Helper method for extending one object with another
    function extend(a, b) {
        for (var i in b) {
            if (b.hasOwnProperty(i)) {
                var g = Object.getOwnPropertyDescriptor(b, i).get;
                var s = Object.getOwnPropertyDescriptor(b, i).set;

                if (g || s) {
                    if (g && s) {
                        Object.defineProperty(a, i, {
                            get: g,
                            set: s
                        });
                    } else if (g) {
                        Object.defineProperty(a, i, {
                            get: g
                        });
                    } else {
                        Object.defineProperty(a, i, {
                            set: s
                        });
                    }
                } else {
                    a[i] = b[i];
                }
            }
        }
        return a;
    }

    window.screen = {};
    window.innerWidth = 0;

    window.assert = function(condition, text) {
        if (!condition) {
            throw 'ASSERTION FAILED' + (text ? ': ' + text : '');
        }
    };

    // Using an implementation of XMLHttpRequest that uses java.net.URL is asking for troubles
    // since socket connections are pooled but there is no control on how they're used, when
    // they are closed and how many of them are opened.
    // When using java.net.URL it happens that a long poll can be closed at any time,
    // just to be reissued using another socket.
    // Therefore we use helper classes that are based on Jetty's HttpClient, which offers full control.
    var XMLHttpRequestExchange = Java.type('org.cometd.javascript.XMLHttpRequestExchange');
    window.XMLHttpRequest = function() {
    };
    window.XMLHttpRequest.UNSENT = 0;
    window.XMLHttpRequest.OPENED = 1;
    window.XMLHttpRequest.HEADERS_RECEIVED = 2;
    window.XMLHttpRequest.LOADING = 3;
    window.XMLHttpRequest.DONE = 4;
    window.XMLHttpRequest.prototype = function() {
        return {
            get readyState() {
                return this._exchange.readyState;
            },
            get responseText() {
                return this._exchange.responseText;
            },
            get responseXML() {
                return null;
            },
            get status() {
                return this._exchange.responseStatus;
            },
            get statusText() {
                return this._exchange.responseStatusText;
            },
            onreadystatechange: function() {
                // Dojo does not override this function (but uses a timer to poll state)
                // so we do not throw if this function is called like we do with WebSocket below.
            },
            onload: function() {
            },
            onerror: function() {
            },
            onabort: function() {
            },
            open: function(method, url, async) {
                // Abort previous exchange
                this.abort();

                var absolute = /^https?:\/\//.test(url);
                var absoluteURL = absolute ? url : window.location.href + url;
                this._exchange = new XMLHttpRequestExchange(xhrClient, javaScript, this, method, absoluteURL, async);
            },
            setRequestHeader: function(header, value) {
                if (this.readyState !== XMLHttpRequest.OPENED) {
                    throw 'INVALID_STATE_ERR: ' + this.readyState;
                }
                if (!header) {
                    throw 'SYNTAX_ERR';
                }
                if (value) {
                    this._exchange.addRequestHeader(header, value);
                }
            },
            send: function(data) {
                if (this.readyState !== XMLHttpRequest.OPENED) {
                    throw 'INVALID_STATE_ERR';
                }
                if (this._exchange.method === 'GET') {
                    data = null;
                }
                if (data) {
                    this._exchange.setRequestContent(data);
                }
                this._exchange.send();
            },
            abort: function() {
                if (this._exchange) {
                    this._exchange.abort();
                }
            },
            getAllResponseHeaders: function() {
                if (this.readyState === XMLHttpRequest.UNSENT || this.readyState === XMLHttpRequest.OPENED) {
                    throw 'INVALID_STATE_ERR';
                }
                return this._exchange.getAllResponseHeaders();
            },
            getResponseHeader: function(header) {
                if (this.readyState === XMLHttpRequest.UNSENT || this.readyState === XMLHttpRequest.OPENED) {
                    throw 'INVALID_STATE_ERR';
                }
                return this._exchange.getResponseHeader(header);
            },
            get withCredentials() {
                return !!this._withCredentials;
            },
            set withCredentials(val) {
                this._withCredentials = val;
            }
        };
    }();

    var WebSocketConnection = Java.type('org.cometd.javascript.WebSocketConnection');
    var wsIds = 0;
    window.WebSocket = function(url, protocol) {
        this._id = ++wsIds;
        this._url = url;
        this._ws = new WebSocketConnection(javaScript, this, wsConnector, url, protocol ? protocol : null);
    };
    window.WebSocket.CONNECTING = 0;
    window.WebSocket.OPEN = 1;
    window.WebSocket.CLOSING = 2;
    window.WebSocket.CLOSED = 3;
    window.WebSocket.prototype = function() {
        return {
            get url() {
                return this._url;
            },
            onopen: function() {
                window.assert(false, "onopen not assigned");
            },
            onerror: function() {
                window.assert(false, "onerror not assigned");
            },
            onclose: function() {
                window.assert(false, "onclose not assigned");
            },
            onmessage: function() {
                window.assert(false, "onmessage not assigned");
            },
            send: function(data) {
                this._ws.send(data);
            },
            close: function(code, reason) {
                this._ws.close(code, reason);
            }
        };
    }();

    window.sessionStorage = sessionStorage;

    window.ArrayBuffer = function(length) {
        this._byteBuffer = Packages.java.nio.ByteBuffer.allocate(length);
    };
    window.ArrayBuffer.prototype = {
        get byteLength() {
            return this._byteBuffer.capacity();
        },
        get _buffer() {
            return this._byteBuffer;
        }
    };

    window.DataView = function(buffer, offset, length) {
        this._buffer = buffer;
        this._offset = offset || 0;
        this._length = length || buffer.byteLength;
        var bb = buffer._buffer;
        var position = bb.position();
        var limit = bb.limit();
        bb.limit(position + this._offset + this._length);
        bb.position(position + this._offset);
        this._view = bb.slice();
        bb.position(position);
        bb.limit(limit);
    };
    window.DataView.prototype = {
        get buffer() {
            return this._buffer;
        },
        get byteLength() {
            return this._length;
        },
        get byteOffset() {
            return this._offset;
        },
        getUint8: function(offset) {
            return this._view.get(offset) & 0xFF;
        },
        setUint8: function(offset, value) {
            this._view.put(offset, Packages.java.lang.Integer.valueOf(value).byteValue());
        }
    };
})();
