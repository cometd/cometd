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
    var _opened = false;
    var _connected = false;
    var _successCallback;

    function _websocketConnect()
    {
        // Mangle the URL, changing the scheme from 'http' to 'ws'
        var url = _cometd.getURL().replace(/^http/, 'ws');
        this._debug('Transport', this.getType(), 'connecting to URL', url);

        var webSocket = new org.cometd.WebSocket(url);
        var self = this;
        webSocket.onopen = function()
        {
            self._debug('WebSocket opened', webSocket);
            if (webSocket !== _webSocket)
            {
                // It's possible that the onopen callback is invoked
                // with a delay so that we have already reconnected
                self._debug('Ignoring open event, WebSocket', _webSocket);
                return;
            }
            self.onOpen();
        };
        webSocket.onclose = function(event)
        {
            var code = event ? event.code : 1000;
            var reason = event ? event.reason : undefined;
            self._debug('WebSocket closed', code, '/', reason, webSocket);
            if (webSocket !== _webSocket)
            {
                // The onclose callback may be invoked when the server sends
                // the close message reply, but after we have already reconnected
                self._debug('Ignoring close event, WebSocket', _webSocket);
                return;
            }
            self.onClose(code, reason);
        };
        webSocket.onerror = function()
        {
            webSocket.onclose({ code: 1002 });
        };
        webSocket.onmessage = function(message)
        {
            self._debug('WebSocket message', message, webSocket);
            if (webSocket !== _webSocket)
            {
                self._debug('Ignoring message event, WebSocket', _webSocket);
                return;
            }
            self.onMessage(message);
        };

        _webSocket = webSocket;
        this._debug('Transport', this.getType(), 'configured callbacks on', webSocket);
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
                var self = this;
                _timeouts[message.id] = this.setTimeout(function()
                {
                    if (_webSocket)
                    {
                        _webSocket.close(1000, 'Timeout');
                    }
                }, delay);
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
            // We may have a non-null _webSocket, but not be open yet so
            // to avoid out of order deliveries, we check if we are open
            else if (_opened)
            {
                _webSocketSend.call(this, envelope, metaConnect);
            }
        }
        catch (x)
        {
            // Keep the semantic of calling response callbacks asynchronously after the request
            this.setTimeout(function()
            {
                envelope.onFailure(_webSocket, envelope.messages, 'error', x);
            }, 0);
        }
    }

    _self.onOpen = function()
    {
        this._debug('Transport', this.getType(), 'opened', _webSocket);
        _opened = true;
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

    _self.onMessage = function(wsMessage)
    {
        this._debug('Transport', this.getType(), 'received websocket message', wsMessage, _webSocket);

        var close = false;
        var messages = this.convertToMessages(wsMessage.data);
        var messageIds = [];
        for (var i = 0; i < messages.length; ++i)
        {
            var message = messages[i];

            // Detect if the message is a response to a request we made.
            // If it's a meta message, for sure it's a response;
            // otherwise it's a publish message and publish responses lack the data field
            if (/^\/meta\//.test(message.channel) || message.data === undefined)
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
            _webSocket.close(1000, 'Disconnect');
        }
    };

    _self.onClose = function(code, reason)
    {
        this._debug('Transport', this.getType(), 'closed', code, reason, _webSocket);

        // Remember if we were able to connect
        // This close event could be due to server shutdown, and if it restarts we want to try websocket again
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
            envelope.onFailure(_webSocket, envelope.messages, 'closed ' + code + '/' + reason);
        }
        _envelopes = {};

        if (_webSocket !== null && _opened)
        {
            _webSocket.close(1000, 'Close');
        }
        _opened = false;
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
        return _supportsWebSocket && !!org.cometd.WebSocket && _cometd.websocketEnabled === true;
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

    _self.reset = function()
    {
        _super.reset();
        if (_webSocket !== null && _opened)
        {
            _webSocket.close(1000, 'Reset');
        }
        _supportsWebSocket = true;
        _webSocketSupported = false;
        _timeouts = {};
        _envelopes = {};
        _webSocket = null;
        _opened = false;
        _successCallback = null;
    };

    return _self;
};
