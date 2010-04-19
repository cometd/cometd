/*
 * Simulated browser environment for Rhino
 * Based on the work by by John Resig <http://ejohn.org/> under the MIT License.
 */

// The window object
var window = this;

(function()
{
    // Browser Navigator
    window.navigator = {
        get appVersion()
        {
            return '5.0 (X11; en-US)';
        },
        get userAgent()
        {
            return 'Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.9.0.4) Gecko/2008111318 Ubuntu/8.10 (intrepid) Firefox/3.0.4';
        },
        get language()
        {
            return 'en-US';
        }
    };


    // Setup location properties
    var _location;
    window.__defineSetter__("location", function(url)
    {
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
    });
    window.__defineGetter__("location", function()
    {
        return _location;
    });


    // The output console
    window.console = function()
    {
        function _log(level, args)
        {
            var text = level;
            for (var i = 0; i < args.length; ++i) text += ' ' + args[i];
            var formatter = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss.SSS');
            var log = formatter.format(new java.util.Date());
            log += ' ' + java.lang.Thread.currentThread().getId();
            log += ' ' + text;
            java.lang.System.err.println(log);
        }

        return {
            error: function()
            {
                _log('ERROR:', arguments);
            },
            warn: function()
            {
                _log('WARN:', arguments);
            },
            info: function()
            {
                _log('INFO:', arguments);
            },
            debug: function()
            {
                _log('DEBUG:', arguments);
            },
            log: function()
            {
                _log('', arguments);
            }
        };
    }();


    // Timers
    var _scheduler = new java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    window.setTimeout = function(fn, delay)
    {
        return _scheduler.schedule(new java.lang.Runnable({
            run: function() { threadModel.execute(window, window, fn); }
        }), delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    };
    window.clearTimeout = function(handle)
    {
        if (handle)
            handle.cancel(true);
    };
    window.setInterval = function(fn, period)
    {
        return _scheduler.scheduleWithFixedDelay(new java.lang.Runnable({
            run: function() { threadModel.execute(window, window, fn); }
        }), period, period, java.util.concurrent.TimeUnit.MILLISECONDS);
    };
    window.clearInterval = function(handle)
    {
        handle.cancel(true);
    };


    // Window Events
    var events = [{}];
    window.addEventListener = function(type, fn)
    {
        if (!this.uuid || this == window)
        {
            this.uuid = events.length;
            events[this.uuid] = {};
        }

        if (!events[this.uuid][type])
            events[this.uuid][type] = [];

        if (events[this.uuid][type].indexOf(fn) < 0)
            events[this.uuid][type].push(fn);
    };
    window.removeEventListener = function(type, fn)
    {
        if (!this.uuid || this == window)
        {
            this.uuid = events.length;
            events[this.uuid] = {};
        }

        if (!events[this.uuid][type])
            events[this.uuid][type] = [];

        events[this.uuid][type] =
        events[this.uuid][type].filter(function(f)
        {
            return f != fn;
        });
    };
    window.dispatchEvent = function(event)
    {
        window.console.debug('Event: ', event);
        if (event.type)
        {
            var self = this;

            if (this.uuid && events[this.uuid][event.type])
            {
                events[this.uuid][event.type].forEach(function(fn)
                {
                    fn.call(self, event);
                });
            }

            if (this["on" + event.type])
                this["on" + event.type].call(self, event);
        }
    };

    /**
     * Performs a GET request to retrieve the content of the given URL,
     * simulating the behavior of a browser calling the URL of the src
     * attribute of the script tag.
     *
     * @param url the URL to make the request to
     */
    function makeScriptRequest(url)
    {
        var xhr = new XMLHttpRequest();
        xhr.open("GET", url, true);
        xhr.onreadystatechange = function()
        {
            if (this.readyState === XMLHttpRequest.DONE && this.status === 200)
            {
                eval(this.responseText);
            }
        };
        xhr.send();
    }

    var _domNodes = new java.util.HashMap();
    /**
     * Helper method for generating the right javascript DOM objects based upon the node type.
     * If the java node exists, returns it, otherwise creates a corresponding javascript node.
     * @param javaNode the java node to convert to javascript node
     */
    function makeNode(javaNode)
    {
        if (!javaNode) return null;
        if (_domNodes.containsKey(javaNode))
            return _domNodes.get(javaNode);
        var isElement = javaNode.getNodeType() == Packages.org.w3c.dom.Node.ELEMENT_NODE;
        var jsNode = isElement ? new DOMElement(javaNode) : new DOMNode(javaNode);
        _domNodes.put(javaNode, jsNode);
        return jsNode;
    }


    // DOM Node
    window.DOMNode = function(node)
    {
        this._dom = node;
    };
    DOMNode.prototype = {
        // START OFFICIAL DOM
        get nodeName()
        {
            return this._dom.getNodeName();
        },
        get nodeValue()
        {
            return this._dom.getNodeValue();
        },
        get nodeType()
        {
            return this._dom.getNodeType();
        },
        get parentNode()
        {
            return makeNode(this._dom.getParentNode());
        },
        get childNodes()
        {
            return new DOMNodeList(this._dom.getChildNodes());
        },
        get firstChild()
        {
            return makeNode(this._dom.getFirstChild());
        },
        get lastChild()
        {
            return makeNode(this._dom.getLastChild());
        },
        get previousSibling()
        {
            return makeNode(this._dom.getPreviousSibling());
        },
        get nextSibling()
        {
            return makeNode(this._dom.getNextSibling());
        },
        get attributes()
        {
            var jsAttributes = {};
            var javaAttributes = this._dom.getAttributes();
            for (var i = 0; i < javaAttributes.getLength(); ++i)
            {
                var javaAttribute = javaAttributes.item(i);
                jsAttributes[javaAttribute.nodeName] = javaAttribute.nodeValue;
            }
            return jsAttributes;
        },
        get ownerDocument()
        {
            return _domNodes.get(this._dom.ownerDocument);
        },
        insertBefore: function(node, before)
        {
            return makeNode(this._dom.insertBefore(node._dom, before ? before._dom : before));
        },
        replaceChild: function(newNode, oldNode)
        {
            return makeNode(this._dom.replaceChild(newNode._dom, oldNode._dom));
        },
        removeChild: function(node)
        {
            return makeNode(this._dom.removeChild(node._dom));
        },
        appendChild: function(node)
        {
            return makeNode(this._dom.appendChild(node._dom));
        },
        hasChildNodes: function()
        {
            return this._dom.hasChildNodes();
        },
        cloneNode: function(deep)
        {
            return makeNode(this._dom.cloneNode(deep));
        },
        normalize: function()
        {
            this._dom.normalize();
        },
        isSupported: function(feature, version)
        {
            return this._dom.isSupported(feature, version);
        },
        get namespaceURI()
        {
            return this._dom.getNamespaceURI();
        },
        get prefix()
        {
            return this._dom.getPrefix();
        },
        set prefix(value)
        {
            this._dom.setPrefix(value);
        },
        get localName()
        {
            return this._dom.getLocalName();
        },
        hasAttributes: function()
        {
            return this._dom.hasAttributes();
        },
        // END OFFICIAL DOM

        addEventListener: window.addEventListener,
        removeEventListener: window.removeEventListener,
        dispatchEvent: window.dispatchEvent,

        get documentElement()
        {
            return makeNode(this._dom.documentElement);
        },
        toString: function()
        {
            return '"' + this.nodeValue + '"';
        },
        get outerHTML()
        {
            return this.nodeValue;
        }
    };


    // DOM Document
    window.DOMDocument = function(stream)
    {
        this._file = stream;
        this._dom = Packages.javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);

        if (!_domNodes.containsKey(this._dom))
            _domNodes.put(this._dom, this);

        this._dom.addEventListener('DOMNodeInserted', Packages.org.cometd.javascript.ScriptInjectionEventListener(threadModel, window, window, makeScriptRequest), false);
    };
    DOMDocument.prototype = extend(new DOMNode(), {
        // START OFFICIAL DOM
//        doctype
//        implementation
        get documentElement()
        {
            return makeNode(this._dom.getDocumentElement());
        },
        createElement: function(name)
        {
            return makeNode(this._dom.createElement(name.toLowerCase()));
        },
        createDocumentFragment: function()
        {
            return makeNode(this._dom.createDocumentFragment());
        },
        createTextNode: function(text)
        {
            return makeNode(this._dom.createTextNode(text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")));
        },
        createComment: function(text)
        {
            return makeNode(this._dom.createComment(text));
        },
//        createCDATASection
//        createProcessingInstruction
//        createAttribute
//        createEntityReference
        getElementsByTagName: function(name)
        {
            return new DOMNodeList(this._dom.getElementsByTagName(
                    name.toLowerCase()));
        },
        importNode: function(node, deep)
        {
            return makeNode(this._dom.importNode(node._dom, deep));
        },
//        createElementNS
//        createAttributeNS
//        getElementsByTagNameNS
        getElementById: function(id)
        {
            var elems = this._dom.getElementsByTagName("*");

            for (var i = 0; i < elems.length; i++)
            {
                var elem = elems.item(i);
                if (elem.getAttribute("id") == id)
                    return makeNode(elem);
            }

            return null;
        },
        // END OFFICIAL DOM

        get body()
        {
            return this.getElementsByTagName("body")[0];
        },
        get ownerDocument()
        {
            return null;
        },
        get nodeName()
        {
            return "#document";
        },
        toString: function()
        {
            return "Document" + (typeof this._file == "string" ?
                                 ": " + this._file : "");
        },
        get innerHTML()
        {
            return this.documentElement.outerHTML;
        },
        get defaultView()
        {
            return {
                getComputedStyle: function(elem)
                {
                    return {
                        getPropertyValue: function(prop)
                        {
                            prop = prop.replace(/\-(\w)/g, function(m, c)
                            {
                                return c.toUpperCase();
                            });
                            var val = elem.style[prop];

                            if (prop == "opacity" && val == "")
                                val = "1";

                            return val;
                        }
                    };
                }
            };
        },
        createEvent: function()
        {
            return {
                type: "",
                initEvent: function(type)
                {
                    this.type = type;
                }
            };
        },
        get cookie()
        {
            return cookies.get(window.location.protocol, window.location.host, window.location.pathname);
        },
        set cookie(value)
        {
            cookies.set(window.location.protocol, window.location.host, window.location.pathname, value);
        }
    });


    // DOM NodeList
    window.DOMNodeList = function(list)
    {
        this._dom = list;
        this.length = list.getLength();

        for (var i = 0; i < this.length; i++)
        {
            var node = list.item(i);
            this[i] = makeNode(node);
        }
    };
    DOMNodeList.prototype = {
        toString: function()
        {
            return "[ " +
                   Array.prototype.join.call(this, ", ") + " ]";
        },
        get outerHTML()
        {
            return Array.prototype.map.call(
                    this, function(node)
            {return node.outerHTML;}).join('');
        }
    };


    // DOM Element
    window.DOMElement = function(elem)
    {
        this._dom = elem;
        this.style = {
            get opacity()
            {
                return this._opacity;
            },
            set opacity(val)
            {
                this._opacity = val + "";
            }
        };

        // Load CSS info
        var styles = (this.getAttribute("style") || "").split(/\s*;\s*/);

        for (var i = 0; i < styles.length; i++)
        {
            var style = styles[i].split(/\s*:\s*/);
            if (style.length == 2)
                this.style[ style[0] ] = style[1];
        }
    };
    DOMElement.prototype = extend(new DOMNode(), {
        // START OFFICIAL DOM
        get tagName()
        {
            return this._dom.getTagName();
        },
        getAttribute: function(name)
        {
            return this._dom.hasAttribute(name) ? new String(this._dom.getAttribute(name)) : null;
        },
        setAttribute: function(name, value)
        {
            this._dom.setAttribute(name, value);
        },
        removeAttribute: function(name)
        {
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
        hasAttribute: function(name)
        {
            return this._dom.hasAttribute(name);
        },
//        hasAttributeNS
        // END OFFICIAL DOM

        get nodeName()
        {
            return this.tagName.toUpperCase();
        },
        toString: function()
        {
            return "<" + this.tagName + (this.id ? "#" + this.id : "" ) + ">";
        },
        get outerHTML()
        {
            var ret = "<" + this.tagName, attr = this.attributes;

            for (var i in attr)
                ret += " " + i + "='" + attr[i] + "'";

            if (this.childNodes.length || this.nodeName == "SCRIPT")
                ret += ">" + this.childNodes.outerHTML +
                       "</" + this.tagName + ">";
            else
                ret += "/>";

            return ret;
        },
        get innerHTML()
        {
            return this.childNodes.outerHTML;
        },
        set innerHTML(html)
        {
            html = html.replace(/<\/?([A-Z]+)/g, function(m)
            {
                return m.toLowerCase();
            });

            var nodes = this.ownerDocument.importNode(
                    new DOMDocument(new java.io.ByteArrayInputStream(
                            (new java.lang.String("<wrap>" + html + "</wrap>"))
                                    .getBytes("UTF8"))).documentElement, true).childNodes;

            while (this.firstChild)
                this.removeChild(this.firstChild);

            for (var i = 0; i < nodes.length; i++)
                this.appendChild(nodes[i]);
        },
        get textContent()
        {
            return nav(this.childNodes);

            function nav(nodes)
            {
                var str = "";
                for (var i = 0; i < nodes.length; i++)
                    if (nodes[i].nodeType == 3)
                        str += nodes[i].nodeValue;
                    else if (nodes[i].nodeType == 1)
                        str += nav(nodes[i].childNodes);
                return str;
            }
        },
        set textContent(text)
        {
            while (this.firstChild)
                this.removeChild(this.firstChild);
            this.appendChild(this.ownerDocument.createTextNode(text));
        },
        style: {},
        clientHeight: 0,
        clientWidth: 0,
        offsetHeight: 0,
        offsetWidth: 0,
        get disabled()
        {
            var val = this.getAttribute("disabled");
            return val != "false" && !!val;
        },
        set disabled(val)
        {
            return this.setAttribute("disabled", val);
        },
        get checked()
        {
            var val = this.getAttribute("checked");
            return val != "false" && !!val;
        },
        set checked(val)
        {
            return this.setAttribute("checked", val);
        },
        get selected()
        {
            if (!this._selectDone)
            {
                this._selectDone = true;

                if (this.nodeName == "OPTION" && !this.parentNode.getAttribute("multiple"))
                {
                    var opt = this.parentNode.getElementsByTagName("option");

                    if (this == opt[0])
                    {
                        var select = true;

                        for (var i = 1; i < opt.length; i++)
                            if (opt[i].selected)
                            {
                                select = false;
                                break;
                            }

                        if (select)
                            this.selected = true;
                    }
                }
            }
            var val = this.getAttribute("selected");
            return val != "false" && !!val;
        },
        set selected(val)
        {
            return this.setAttribute("selected", val);
        },
        get className()
        {
            return this.getAttribute("class") || "";
        },
        set className(val)
        {
            return this.setAttribute("class", val.replace(/(^\s*|\s*$)/g, ""));
        },
        get type()
        {
            return this.getAttribute("type") || "";
        },
        set type(val)
        {
            return this.setAttribute("type", val);
        },
        get value()
        {
            return this.getAttribute("value") || "";
        },
        set value(val)
        {
            return this.setAttribute("value", val);
        },
        get src()
        {
            return this.getAttribute("src") || "";
        },
        set src(val)
        {
            return this.setAttribute("src", val);
        },
        get id()
        {
            return this.getAttribute("id") || "";
        },
        set id(val)
        {
            return this.setAttribute("id", val);
        },
        click: function()
        {
            var event = document.createEvent();
            event.initEvent("click");
            this.dispatchEvent(event);
        },
        submit: function()
        {
            var event = document.createEvent();
            event.initEvent("submit");
            this.dispatchEvent(event);
        },
        focus: function()
        {
            var event = document.createEvent();
            event.initEvent("focus");
            this.dispatchEvent(event);
        },
        blur: function()
        {
            var event = document.createEvent();
            event.initEvent("blur");
            this.dispatchEvent(event);
        },
        get elements()
        {
            return this.getElementsByTagName("*");
        },
        get contentWindow()
        {
            return this.nodeName == "IFRAME" ? {
                document: this.contentDocument
            } : null;
        },
        get contentDocument()
        {
            if (this.nodeName == "IFRAME")
            {
                if (!this._doc)
                    this._doc = new DOMDocument(
                            new java.io.ByteArrayInputStream((new java.lang.String(
                                    "<html><head><title></title></head><body></body></html>"))
                                    .getBytes("UTF8")));
                return this._doc;
            }
            else
                return null;
        }
    });


    // Fake document object
    window.document = new DOMDocument(new java.io.ByteArrayInputStream(
            (new java.lang.String("<html><head><title></title></head><body></body></html>")).getBytes("UTF8")));


    // Helper method for extending one object with another
    function extend(a, b)
    {
        for (var i in b)
        {
            var g = b.__lookupGetter__(i), s = b.__lookupSetter__(i);

            if (g || s)
            {
                if (g)
                    a.__defineGetter__(i, g);
                if (s)
                    a.__defineSetter__(i, s);
            }
            else
                a[i] = b[i];
        }
        return a;
    }

    // Using an implementation of XMLHttpRequest that uses java.net.URL is asking for troubles
    // since socket connections are pooled but there is no control on how they're used, when
    // they are closed and how many of them are opened.
    // When using java.net.URL it happens that a long poll can be closed at any time,
    // just to be reissued using another socket.
    // Therefore we use helper classes that are based on Jetty's HttpClient, which offers full control.
    var _xhrClient = new XMLHttpRequestClient(maxConnections || 2);
    window.XMLHttpRequest = function() {};
    XMLHttpRequest.UNSENT = 0;
    XMLHttpRequest.OPENED = 1;
    XMLHttpRequest.HEADERS_RECEIVED = 2;
    XMLHttpRequest.LOADING = 3;
    XMLHttpRequest.DONE = 4;
    XMLHttpRequest.prototype = function()
    {
        return {
            get readyState()
            {
                return this._exchange.readyState;
            },
            get responseText()
            {
                return this._exchange.responseText;
            },
            get responseXML()
            {
                return null; // TODO
            },
            get status()
            {
                return this._exchange.responseStatus;
            },
            get statusText()
            {
                return this._exchange.responseStatusText;
            },
            onreadystatechange: function()
            {
            },
            open: function(method, url, async, user, password)
            {
                // Abort previous exchange
                this.abort();

                var absolute = /^https?:\/\//.test(url);
                var absoluteURL = absolute ? url : window.location.href + url;
                this._exchange = new XMLHttpRequestExchange(threadModel, this, this, this.onreadystatechange, method, absoluteURL, async);
            },
            setRequestHeader: function(header, value)
            {
                if (this.readyState !== XMLHttpRequest.OPENED) throw 'INVALID_STATE_ERR: ' + this.readyState;
                if (!header) throw 'SYNTAX_ERR';
                if (value) this._exchange.addRequestHeader(header, value);
            },
            send: function(data)
            {
                if (this.readyState !== XMLHttpRequest.OPENED) throw 'INVALID_STATE_ERR';
                this._exchange.setOnReadyStateChange(this, this.onreadystatechange);
                if (this._exchange.method == 'GET') data = null;
                if (data) this._exchange.setRequestContent(data);
                _xhrClient.send(this._exchange);
            },
            abort: function()
            {
                if (this._exchange) this._exchange.cancel();
            },
            getAllResponseHeaders: function()
            {
                if (this.readyState === XMLHttpRequest.UNSENT || this.readyState === XMLHttpRequest.OPENED)
                    throw 'INVALID_STATE_ERR';
                return this._exchange.getAllResponseHeaders();
            },
            getResponseHeader: function(header)
            {
                if (this.readyState === XMLHttpRequest.UNSENT || this.readyState === XMLHttpRequest.OPENED)
                    throw 'INVALID_STATE_ERR';
                return this._exchange.getResponseHeader(header);
            }
        };
    }();

    window.assert = function(condition, text)
    {
        if (!condition) throw 'ASSERTION FAILED' + (text ? ': ' + text : '');
    }

})();
