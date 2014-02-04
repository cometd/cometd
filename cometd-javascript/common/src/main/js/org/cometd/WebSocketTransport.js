org.cometd.WebSocketTransport = function()
{
    var _super = new org.cometd.Transport();
    var _self = org.cometd.Transport.derive(_super);
    var _cometd;
    // By default WebSocket is supported
    var _webSocketSupported = true;
    // Whether we were able to establish a WebSocket connection
    var _webSocketConnected = false;
    var _stickyReconnect = true;
    // Envelopes that have been sent
    var _envelopes = {};
    // Timeouts for messages that have been sent
    var _timeouts = {};
    var _connecting = false;
    var _webSocket = null;
    var _connected = false;
    var _successCallback = null;

    _self.reset = function()
    {
        _super.reset();
        _webSocketSupported = true;
        _webSocketConnected = false;
        _stickyReconnect = true;
        _envelopes = {};
        _timeouts = {};
        _connecting = false;
        _webSocket = null;
        _connected = false;
        _successCallback = null;
    };

    function _websocketConnect()
    {
        // We may have multiple attempts to open a WebSocket
        // connection, for example a /meta/connect request that
        // may take time, along with a user-triggered publish.
        // Early return if we are connecting.
        if (_connecting)
        {
            return;
        }

        _connecting = true;

        // Mangle the URL, changing the scheme from 'http' to 'ws'.
        var url = _cometd.getURL().replace(/^http/, 'ws');
        this._debug('Transport', this.getType(), 'connecting to URL', url);

        try
        {
            var protocol = _cometd.getConfiguration().protocol;
            var webSocket = protocol ? new org.cometd.WebSocket(url, protocol) : new org.cometd.WebSocket(url);
        }
        catch (x)
        {
            _webSocketSupported = false;
            this._debug('Exception while creating WebSocket object', x);
            throw x;
        }

        // By default use sticky reconnects.
        _stickyReconnect = _cometd.getConfiguration().stickyReconnect !== false;

        var self = this;
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
                self.webSocketClose(webSocket, event.code, event.reason);
                // Force immediate failure of pending messages to trigger reconnect.
                // This is needed because the server may not reply to our close()
                // and therefore the onclose function is never called.
                self.onClose(webSocket, event);
            }, connectTimeout);
        }

        var onopen = function()
        {
            self._debug('WebSocket opened', webSocket);
            _connecting = false;
            if (connectTimer)
            {
                self.clearTimeout(connectTimer);
                connectTimer = null;
            }

            if (_webSocket)
            {
                // We have a valid connection already, close this one.
                _cometd._warn('Closing Extra WebSocket Connections', webSocket, _webSocket);
                // Closing will eventually trigger onclose(), but
                // we do not want to clear outstanding messages.
                self.webSocketClose(webSocket, 1000, 'Extra Connection');
            }
            else
            {
                self.onOpen(webSocket);
            }
        };
        // This callback is invoked when the server sends the close frame.
        var onclose = function(event)
        {
            event = event || { code: 1000 };
            self._debug('WebSocket closing', webSocket, event);
            _connecting = false;
            if (connectTimer)
            {
                self.clearTimeout(connectTimer);
                connectTimer = null;
            }

            if (_webSocket !== null && webSocket !== _webSocket)
            {
                // We closed an extra WebSocket object that
                // we may have created during reconnection.
                self._debug('Closed Extra WebSocket Connection', webSocket);
            }
            else
            {
                self.onClose(webSocket, event);
            }
        };
        var onmessage = function(message)
        {
            self._debug('WebSocket message', message, webSocket);
            if (webSocket !== _webSocket)
            {
                _cometd._warn('Extra WebSocket Connections', webSocket, _webSocket);
            }
            self.onMessage(webSocket, message);
        };

        webSocket.onopen = onopen;
        webSocket.onclose = onclose;
        webSocket.onerror = function()
        {
            // Clients should call onclose(), but if they do not we do it here for safety.
            onclose({ code: 1002, reason: 'Error' });
        };
        webSocket.onmessage = onmessage;

        this._debug('Transport', this.getType(), 'configured callbacks on', webSocket);
    }

    function _webSocketSend(webSocket, envelope, metaConnect)
    {
        var json = org.cometd.JSON.toJSON(envelope.messages);

        webSocket.send(json);
        this._debug('Transport', this.getType(), 'sent', envelope, 'metaConnect =', metaConnect);

        // Manage the timeout waiting for the response.
        var maxDelay = this.getConfiguration().maxNetworkDelay;
        var delay = maxDelay;
        if (metaConnect)
        {
            delay += this.getAdvice().timeout;
            _connected = true;
        }

        var self = this;
        var messageIds = [];
        for (var i = 0; i < envelope.messages.length; ++i)
        {
            (function()
            {
                var message = envelope.messages[i];
                if (message.id)
                {
                    messageIds.push(message.id);
                    _timeouts[message.id] = this.setTimeout(function()
                    {
                        self._debug('Transport', self.getType(), 'timing out message', message.id, 'after', delay, 'on', webSocket);
                        var event = { code: 1000, reason: 'Message Timeout' };
                        self.webSocketClose(webSocket, event.code, event.reason);
                        // Force immediate failure of pending messages to trigger reconnect.
                        // This is needed because the server may not reply to our close()
                        // and therefore the onclose function is never called.
                        self.onClose(webSocket, event);
                    }, delay);
                }
            })();
        }

        this._debug('Transport', this.getType(), 'waiting at most', delay, 'ms for messages', messageIds, 'maxNetworkDelay', maxDelay, ', timeouts:', _timeouts);
    }

    function _send(webSocket, envelope, metaConnect)
    {
        try
        {
            if (webSocket === null)
            {
                _websocketConnect.call(this);
            }
            else
            {
                _webSocketSend.call(this, webSocket, envelope, metaConnect);
            }
        }
        catch (x)
        {
            // Keep the semantic of calling response callbacks asynchronously after the request.
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
        _webSocketConnected = true;

        this._debug('Sending pending messages', _envelopes);
        for (var key in _envelopes)
        {
            var element = _envelopes[key];
            var envelope = element[0];
            var metaConnect = element[1];
            // Store the success callback, which is independent from the envelope,
            // so that it can be used to notify arrival of messages.
            _successCallback = envelope.onSuccess;
            _webSocketSend.call(this, webSocket, envelope, metaConnect);
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
            // a publish message and publish responses have the successful field.
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

        // Remove the envelope corresponding to the messages.
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
        // and if it restarts we want to try websocket again.
        _webSocketSupported = _stickyReconnect && _webSocketConnected;

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
            envelope.onFailure(webSocket, envelope.messages, {
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
        // Using !! to return a boolean (and not the WebSocket object).
        return _webSocketSupported && !!org.cometd.WebSocket && _cometd.websocketEnabled !== false;
    };

    _self.send = function(envelope, metaConnect)
    {
        this._debug('Transport', this.getType(), 'sending', envelope, 'metaConnect =', metaConnect);

        // Store the envelope in any case; if the websocket cannot be opened, we fail it.
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

        _send.call(this, _webSocket, envelope, metaConnect);
    };

    _self.webSocketClose = function(webSocket, code, reason)
    {
        try
        {
            webSocket.close(code, reason);
        }
        catch (x)
        {
            this._debug(x);
        }
    };

    _self.abort = function()
    {
        _super.abort();
        if (_webSocket)
        {
            var event = { code: 1001, reason: 'Abort' };
            this.webSocketClose(_webSocket, event.code, event.reason);
            // Force immediate failure of pending messages to trigger reconnect.
            // This is needed because the server may not reply to our close()
            // and therefore the onclose function is never called.
            this.onClose(_webSocket, event);
        }
        this.reset();
    };

    return _self;
};
