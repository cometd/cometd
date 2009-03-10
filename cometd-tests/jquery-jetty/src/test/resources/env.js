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
        get userAgent()
        {
            return 'Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.9.0.4) Gecko/2008111318 Ubuntu/8.10 (intrepid) Firefox/3.0.4';
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
        function _log(text)
        {
            var formatter = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss.SSS');
            var log = formatter.format(new java.util.Date());
            log += ' ' + java.lang.Thread.currentThread().getId();
            log += ' ' + text;
            java.lang.System.out.println(log);
        }

        return {
            error: function(text)
            {
                _log('ERROR: ' + text);
            },
            warn: function(text)
            {
                _log('WARN : ' + text);
            },
            info: function(text)
            {
                _log('INFO : ' + text);
            },
            debug: function(text)
            {
                _log('DEBUG: ' + text);
            },
            log: function(text)
            {
                _log(text);
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
        if (event.type)
        {
            if (this.uuid && events[this.uuid][event.type])
            {
                var self = this;

                events[this.uuid][event.type].forEach(function(fn)
                {
                    fn.call(self, event);
                });
            }

            if (this["on" + event.type])
                this["on" + event.type].call(self, event);
        }
    };


    // Helper method for generating the right DOM objects based upon the type
    var _objNodes = new java.util.HashMap();
    function makeNode(node)
    {
        if (node)
        {
            if (!_objNodes.containsKey(node))
                _objNodes.put(node, node.getNodeType() ==
                                    Packages.org.w3c.dom.Node.ELEMENT_NODE ?
                                    new DOMElement(node) : new DOMNode(node));

            return _objNodes.get(node);
        }
        else
            return null;
    }


    // DOM Document
    window.DOMDocument = function(stream)
    {
        this._file = stream;
        this._dom = Packages.javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);

        if (!_objNodes.containsKey(this._dom))
            _objNodes.put(this._dom, this);
    };
    DOMDocument.prototype = {
        createTextNode: function(text)
        {
            return makeNode(this._dom.createTextNode(
                    text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")));
        },
        createComment: function(text)
        {
            return makeNode(this._dom.createComment(text));
        },
        createElement: function(name)
        {
            return makeNode(this._dom.createElement(name.toLowerCase()));
        },
        getElementsByTagName: function(name)
        {
            return new DOMNodeList(this._dom.getElementsByTagName(
                    name.toLowerCase()));
        },
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
        get body()
        {
            return this.getElementsByTagName("body")[0];
        },
        get documentElement()
        {
            return makeNode(this._dom.getDocumentElement());
        },
        get ownerDocument()
        {
            return null;
        },
        addEventListener: window.addEventListener,
        removeEventListener: window.removeEventListener,
        dispatchEvent: window.dispatchEvent,
        get nodeName()
        {
            return "#document";
        },
        importNode: function(node, deep)
        {
            return makeNode(this._dom.importNode(node._dom, deep));
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
        }
    };

    function getDocument(node)
    {
        return _objNodes.get(node);
    }


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


    // DOM Node
    window.DOMNode = function(node)
    {
        this._dom = node;
    };
    DOMNode.prototype = {
        get nodeType()
        {
            return this._dom.getNodeType();
        },
        get nodeValue()
        {
            return this._dom.getNodeValue();
        },
        get nodeName()
        {
            return this._dom.getNodeName();
        },
        cloneNode: function(deep)
        {
            return makeNode(this._dom.cloneNode(deep));
        },
        get ownerDocument()
        {
            return getDocument(this._dom.ownerDocument);
        },
        get documentElement()
        {
            return makeNode(this._dom.documentElement);
        },
        get parentNode()
        {
            return makeNode(this._dom.getParentNode());
        },
        get nextSibling()
        {
            return makeNode(this._dom.getNextSibling());
        },
        get previousSibling()
        {
            return makeNode(this._dom.getPreviousSibling());
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
        get nodeName()
        {
            return this.tagName.toUpperCase();
        },
        get tagName()
        {
            return this._dom.getTagName();
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
        get attributes()
        {
            var attr = {}, attrs = this._dom.getAttributes();

            for (var i = 0; i < attrs.getLength(); i++)
                attr[ attrs.item(i).nodeName ] = attrs.item(i).nodeValue;

            return attr;
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
        appendChild: function(node)
        {
            this._dom.appendChild(node._dom);
        },
        insertBefore: function(node, before)
        {
            this._dom.insertBefore(node._dom, before ? before._dom : before);
        },
        removeChild: function(node)
        {
            this._dom.removeChild(node._dom);
        },
        getElementsByTagName: DOMDocument.prototype.getElementsByTagName,
        addEventListener: window.addEventListener,
        removeEventListener: window.removeEventListener,
        dispatchEvent: window.dispatchEvent,
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
                if (async !== undefined && !async) throw 'NOT_SUPPORTED_ERR';

                // Abort previous exchange
                this.abort();

                var absolute = /^https?:\/\//.test(url);
                var absoluteURL = absolute ? url : window.location.href + url;
                this._exchange = new XMLHttpRequestExchange(threadModel, this, this, this.onreadystatechange, method, absoluteURL);
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
