org.cometd.WebSocketTransport = function()
{
    var OPENED = 1;
    var CLOSED = 2;

    var _super = new org.cometd.Transport();
    var _self = org.cometd.Transport.derive(_super);
    var _cometd;
    // By default, support WebSocket
    var _supportsWebSocket = true;
    var _webSocketSupported = false;
    var _state = CLOSED;
    var _timeouts = {};
    var _envelopes = {};
    var _webSocket;
    var _successCallback;

    _self.registered = function(type, cometd)
    {
        _super.registered(type, cometd);
        _cometd = cometd;
    };

    _self.accept = function(version, crossDomain, url)
    {
        // Using !! to return a boolean (and not the WebSocket object)
        return _supportsWebSocket && !!window.WebSocket && _cometd.websocketEnabled === true;
    };

    function _websocketSend(envelope, metaConnect)
    {
        try
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
                        var errorMessage = 'Message ' + message.id + ' of transport ' + self.getType() + ' exceeded ' + delay + ' ms max network delay';
                        self._debug(errorMessage);

                        delete _timeouts[message.id];

                        for (var ids in _envelopes)
                        {
                            if (_envelopes[ids] === envelope)
                            {
                                delete _envelopes[ids];
                                break;
                            }
                        }
                        envelope.onFailure(_webSocket, envelope.messages, 'timeout', errorMessage);
                    }, delay);
                }
            }

            this._debug('Transport', this.getType(), 'waiting at most', delay, ' ms for messages', messageIds, 'maxNetworkDelay', maxDelay, ', timeouts:', _timeouts);
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

    _self.onMessage = function(wsMessage)
    {
        this._debug('Transport', this.getType(), 'received websocket message', wsMessage);

        if (_state === OPENED)
        {
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
                            clearTimeout(timeout);
                            delete _timeouts[message.id];
                            this._debug('Transport', this.getType(), 'removed timeout for message', message.id, ', timeouts', _timeouts);
                        }
                    }
                }

                if ('/meta/disconnect' === message.channel && message.successful)
                {
                    _webSocket.close();
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
                        var envelope = _envelopes[key];
                        delete _envelopes[key];
                        if (ids.length > 0)
                        {
                            _envelopes[ids.join(',')] = envelope;
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
        }
    };

    _self.onClose = function()
    {
        this._debug('Transport', this.getType(), 'closed', _webSocket);

        // Remember if we were able to connect
        // This close event could be due to server shutdown, and if it restarts we want to try websocket again
        _supportsWebSocket = _webSocketSupported;

        for (var id in _timeouts)
        {
            clearTimeout(_timeouts[id]);
            delete _timeouts[id];
        }

        for (var ids in _envelopes)
        {
            _envelopes[ids].onFailure(_webSocket, _envelopes[ids].messages, 'closed');
            delete _envelopes[ids];
        }

        _state = CLOSED;
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
        _envelopes[messageIds.join(',')] = envelope;
        this._debug('Transport', this.getType(), 'stored envelope, envelopes', _envelopes);

        if (_state === OPENED)
        {
            _websocketSend.call(this, envelope, metaConnect);
        }
        else
        {
            // Mangle the URL, changing the scheme from 'http' to 'ws'
            var url = envelope.url.replace(/^http/, 'ws');
            this._debug('Transport', this.getType(), 'connecting to URL', url);

            _webSocket = new window.WebSocket(url);
            var self = this;
            _webSocket.onopen = function()
            {
                self._debug('WebSocket opened', _webSocket);
                _webSocketSupported = true;
                _state = OPENED;
                // Store the success callback, which is independent from the envelope,
                // so that it can be used to notify arrival of messages.
                _successCallback = envelope.onSuccess;
                _websocketSend.call(self, envelope, metaConnect);
            };
            _webSocket.onclose = function()
            {
                self.onClose();
            };
            _webSocket.onmessage = function(message)
            {
                self.onMessage(message);
            };
        }
    };

    _self.reset = function()
    {
        _super.reset();
        if (_webSocket)
        {
            _webSocket.close();
        }
        _supportsWebSocket = true;
        _webSocketSupported = false;
        _state = CLOSED;
        _timeouts = {};
        _envelopes = {};
        _webSocket = null;
        _successCallback = null;
    };

    return _self;
};
