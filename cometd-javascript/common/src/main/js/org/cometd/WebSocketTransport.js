org.cometd.WebSocketTransport = function()
{
    var _super = new org.cometd.Transport();
    var _self = org.cometd.Transport.derive(_super);
    var _cometd;
    // By default, support WebSocket
    var _supportsWebSocket = true;
    // Whether we were able to establish a WebSocket connection
    var _webSocketSupported = false;
    // Envelopes that have been sent
    var _envelopes = {};
    // Timeouts for messages that have been sent
    var _timeouts = {};
    var _webSocket = null;
    var _connected = false;
    var _successCallback;

    function _websocketConnect()
    {
        // Mangle the URL, changing the scheme from 'http' to 'ws'
        var url = _cometd.getURL().replace(/^http/, 'ws');
        this._debug('Transport', this.getType(), 'connecting to URL', url);

        var self = this;
        var webSocket = new org.cometd.WebSocket(url, _cometd.getConfiguration().protocol);

        var connectTimer = null;
        var connectTimeout = _cometd.getConfiguration().connectTimeout;
        if (connectTimeout > 0)
        {
            connectTimer = this.setTimeout(function()
            {
                connectTimer = null;
                self._debug('Transport', self.getType(), 'timed out while connecting to URL', url, ':', connectTimeout, 'ms');
                // The connection was not opened, close anyway.
                var event = { code: 1000, reason: 'Connect Timeout' };
                webSocket.close(event.code, event.reason);
                // Force immediate failure of pending messages to trigger reconnect
                self.onClose(webSocket, event);
            }, connectTimeout);
        }

        var onopen = function()
        {
            self._debug('WebSocket opened', webSocket);
            if (connectTimer)
            {
                self.clearTimeout(connectTimer);
                connectTimer = null;
            }

            // It's possible that the onopen callback is invoked
            // with a delay so that we have already reconnected
            if (_webSocket !== null)
            {
                // We have a valid connection already, close this one
                self._debug('Closing extra WebSocket', webSocket);
                webSocket.close(1000, 'Extra Connect'); // Will eventually trigger onclose()
            }
            else
            {
                self.onOpen(webSocket);
            }
        };
        var onclose = function(event)
        {
            self._debug('WebSocket closing', webSocket, event);
            if (connectTimer)
            {
                self.clearTimeout(connectTimer);
                connectTimer = null;
            }

            // This callback is invoked when the server sends the close frame
            if (_webSocket !== null && webSocket !== _webSocket)
            {
                // We closed an extra WebSocket object that
                // we may have created during reconnection
                self._debug('Closed extra WebSocket', webSocket);
            }
            else
            {
                self.onClose(webSocket, event);
            }
        };
        var onmessage = function(message)
        {
            self._debug('WebSocket message', message, webSocket);
            if (webSocket === _webSocket)
            {
                self.onMessage(webSocket, message);
            }
            else
            {
                _cometd._warn('Multiple WebSocket objects', webSocket, _webSocket);
                throw 'Multiple WebSocket objects';
            }
        };

        webSocket.onopen = onopen;
        webSocket.onclose = onclose;
        webSocket.onerror = function()
        {
            onclose({ code: 1002, reason: 'Error' });
        };
        webSocket.onmessage = onmessage;

        this._debug('Transport', this.getType(), 'configured callbacks on', webSocket);
    }

    function _timeoutFn(webSocket, message, delay)
    {
        var self = this;
        return function()
        {
            self._debug('Transport', self.getType(), 'timing out message', message.id, 'after', delay, 'on', webSocket);
            var event = { code: 1000, reason: 'Message Timeout' };
            self.webSocketClose(webSocket, event.code, event.reason);
            // Force immediate failure of pending messages to trigger reconnect
            self.onClose(webSocket, event);
        };
    }

    function _webSocketSend(envelope, metaConnect)
    {
        var json = org.cometd.JSON.toJSON(envelope.messages);

        _webSocket.send(json);
        this._debug('Transport', this.getType(), 'sent', envelope, 'metaConnect =', metaConnect);

        // Manage the timeout waiting for the response
        var maxDelay = this.getConfiguration().maxNetworkDelay;
        var delay = maxDelay;
        if (metaConnect)
        {
            delay += this.getAdvice().timeout;
            _connected = true;
        }

        var messageIds = [];
        for (var i = 0; i < envelope.messages.length; ++i)
        {
            var message = envelope.messages[i];
            if (message.id)
            {
                messageIds.push(message.id);
                _timeouts[message.id] = this.setTimeout(_timeoutFn.call(this, _webSocket, message, delay), delay);
            }
        }

        this._debug('Transport', this.getType(), 'waiting at most', delay, 'ms for messages', messageIds, 'maxNetworkDelay', maxDelay, ', timeouts:', _timeouts);
    }

    function _send(envelope, metaConnect)
    {
        try
        {
            if (_webSocket === null)
            {
                _websocketConnect.call(this);
            }
            else
            {
                _webSocketSend.call(this, envelope, metaConnect);
            }
        }
        catch (x)
        {
            // Keep the semantic of calling response callbacks asynchronously after the request
            var webSocket = _webSocket;
            this.setTimeout(function()
            {
                envelope.onFailure(webSocket, envelope.messages, {
                    exception: x
                });
            }, 0);
        }
    }

    _self.onOpen = function(webSocket)
    {
        this._debug('Transport', this.getType(), 'opened', webSocket);
        _webSocket = webSocket;
        _webSocketSupported = true;

        this._debug('Sending pending messages', _envelopes);
        for (var key in _envelopes)
        {
            var element = _envelopes[key];
            var envelope = element[0];
            var metaConnect = element[1];
            // Store the success callback, which is independent from the envelope,
            // so that it can be used to notify arrival of messages.
            _successCallback = envelope.onSuccess;
            _webSocketSend.call(this, envelope, metaConnect);
        }
    };

    _self.onMessage = function(webSocket, wsMessage)
    {
        this._debug('Transport', this.getType(), 'received websocket message', wsMessage, webSocket);

        var close = false;
        var messages = this.convertToMessages(wsMessage.data);
        var messageIds = [];
        for (var i = 0; i < messages.length; ++i)
        {
            var message = messages[i];

            // Detect if the message is a response to a request we made.
            // If it's a meta message, for sure it's a response; otherwise it's
            // a publish message and publish responses have the successful field
            if (/^\/meta\//.test(message.channel) || message.successful !== undefined)
            {
                if (message.id)
                {
                    messageIds.push(message.id);

                    var timeout = _timeouts[message.id];
                    if (timeout)
                    {
                        this.clearTimeout(timeout);
                        delete _timeouts[message.id];
                        this._debug('Transport', this.getType(), 'removed timeout for message', message.id, ', timeouts', _timeouts);
                    }
                }
            }

            if ('/meta/connect' === message.channel)
            {
                _connected = false;
            }
            if ('/meta/disconnect' === message.channel && !_connected)
            {
                close = true;
            }
        }

        // Remove the envelope corresponding to the messages
        var removed = false;
        for (var j = 0; j < messageIds.length; ++j)
        {
            var id = messageIds[j];
            for (var key in _envelopes)
            {
                var ids = key.split(',');
                var index = org.cometd.Utils.inArray(id, ids);
                if (index >= 0)
                {
                    removed = true;
                    ids.splice(index, 1);
                    var envelope = _envelopes[key][0];
                    var metaConnect = _envelopes[key][1];
                    delete _envelopes[key];
                    if (ids.length > 0)
                    {
                        _envelopes[ids.join(',')] = [envelope, metaConnect];
                    }
                    break;
                }
            }
        }
        if (removed)
        {
            this._debug('Transport', this.getType(), 'removed envelope, envelopes', _envelopes);
        }

        _successCallback.call(this, messages);

        if (close)
        {
            this.webSocketClose(webSocket, 1000, 'Disconnect');
        }
    };

    _self.onClose = function(webSocket, event)
    {
        this._debug('Transport', this.getType(), 'closed', webSocket, event);

        // Remember if we were able to connect
        // This close event could be due to server shutdown,
        // and if it restarts we want to try websocket again
        _supportsWebSocket = _webSocketSupported;

        for (var id in _timeouts)
        {
            this.clearTimeout(_timeouts[id]);
        }
        _timeouts = {};

        for (var key in _envelopes)
        {
            var envelope = _envelopes[key][0];
            var metaConnect = _envelopes[key][1];
            if (metaConnect)
            {
                _connected = false;
            }
            envelope.onFailure(_webSocket, envelope.messages, {
                websocketCode: event.code,
                reason: event.reason
            });
        }
        _envelopes = {};

        _webSocket = null;
    };

    _self.registered = function(type, cometd)
    {
        _super.registered(type, cometd);
        _cometd = cometd;
    };

    _self.accept = function(version, crossDomain, url)
    {
        // Using !! to return a boolean (and not the WebSocket object)
        return _supportsWebSocket && !!org.cometd.WebSocket && _cometd.websocketEnabled !== false;
    };

    _self.send = function(envelope, metaConnect)
    {
        this._debug('Transport', this.getType(), 'sending', envelope, 'metaConnect =', metaConnect);

        // Store the envelope in any case; if the websocket cannot be opened, we fail it in close()
        var messageIds = [];
        for (var i = 0; i < envelope.messages.length; ++i)
        {
            var message = envelope.messages[i];
            if (message.id)
            {
                messageIds.push(message.id);
            }
        }
        _envelopes[messageIds.join(',')] = [envelope, metaConnect];
        this._debug('Transport', this.getType(), 'stored envelope, envelopes', _envelopes);

        _send.call(this, envelope, metaConnect);
    };

    _self.webSocketClose = function(webSocket, code, reason)
    {
        if (webSocket)
        {
            try
            {
                webSocket.close(code, reason);
            }
            catch (x)
            {
                this._debug(x);
            }
        }
    };

    _self.abort = function()
    {
        _super.abort();
        this.webSocketClose(_webSocket, 1001, 'Abort');
        this.reset();
    };

    _self.reset = function()
    {
        _super.reset();
        _supportsWebSocket = true;
        _webSocketSupported = false;
        _timeouts = {};
        _envelopes = {};
        _webSocket = null;
        _successCallback = null;
    };

    return _self;
};
