org.cometd.WebSocketTransport = function()
{
    var _super = new org.cometd.Transport();
    var that = org.cometd.Transport.derive(_super);
    var _webSocket;
    // By default, support WebSocket
    var _supportsWebSocket = true;
    var _envelope;
    var _state;
    var _metaConnectEnvelope;
    var _timeouts = {};
    var _WebSocket;

    if (window.WebSocket)
    {
        _WebSocket = window.WebSocket;
        _state = _WebSocket.CLOSED;
    }

    function _doSend(envelope, metaConnect)
    {
        if (_webSocket.send(org.cometd.JSON.toJSON(envelope.messages)))
        {
            var delay = this.getConfiguration().maxNetworkDelay;
            if (metaConnect)
            {
                delay += this.getAdvice().timeout;
            }

            for (var i = 0; i < envelope.messages.length; ++i)
            {
                var message = envelope.messages[i];
                if (message.id)
                {
                    var self = this;
                    _timeouts[message.id] = this.setTimeout(function()
                    {
                        delete _timeouts[message.id];
                        var errorMessage = 'TIMEOUT message ' + message.id + ' exceeded ' + delay + 'ms';
                        self._debug(errorMessage);
                        envelope.onFailure(_webSocket, 'timeout', errorMessage);
                    }, delay);
                    this._debug('waiting', delay, ' for  ', message.id, org.cometd.JSON.toJSON(_timeouts));
                }
            }
        }
        else
        {
            // Keep the semantic of calling response callbacks asynchronously after the request
            this.setTimeout(function()
            {
                envelope.onFailure(_webSocket, 'failed', null);
            }, 0);
        }
    }

    that.accept = function(version, crossDomain)
    {
        return _supportsWebSocket && !!_WebSocket;
    };

    that.send = function(envelope, metaConnect)
    {
        this._debug('Transport', this.getType(), this, 'sending', envelope, 'metaConnect', metaConnect);

        // Remember the envelope
        if (metaConnect)
        {
            _metaConnectEnvelope = envelope;
        }
        else
        {
            _envelope = envelope;
        }

        // Do we have an open websocket?
        if (_state === _WebSocket.OPEN)
        {
            _doSend.call(this, envelope, metaConnect);
        }
        else
        {
            // No, so create new websocket

            // Mangle the URL, changing the scheme from 'http' to 'ws'
            var url = envelope.url.replace(/^http/, 'ws');
            this._debug('Transport', this, 'URL', url);

            var self = this;
            var webSocket = new _WebSocket(url);

            webSocket.onopen = function()
            {
                self._debug('Opened', webSocket);
                // once the websocket is open, send the envelope.
                _state = _WebSocket.OPEN;
                _webSocket = webSocket;
                _doSend.call(self, envelope, metaConnect);
            };

            webSocket.onclose = function()
            {
                self._debug('Closed', webSocket);
                if (_state !== _WebSocket.OPEN)
                {
                    _supportsWebSocket = false;
                    envelope.onFailure(webSocket, 'cannot open', null);
                }
                else
                {
                    _state = _WebSocket.CLOSED;
                    // clear all timeouts
                    for (var i in _timeouts)
                    {
                        clearTimeout(_timeouts[i]);
                        delete _timeouts[i];
                    }
                }
            };

            webSocket.onmessage = function(message)
            {
                self._debug('Message', message);
                if (_state === _WebSocket.OPEN)
                {
                    var rcvdMessages = self.convertToMessages(message.data);
                    var mc = false;

                    // scan messages
                    for (var i = 0; i < rcvdMessages.length; ++i)
                    {
                        var msg = rcvdMessages[i];

                        // is this coming with a meta connect response?
                        if ('/meta/connect' == msg.channel)
                        {
                            mc = true;
                        }

                        // cancel and delete any pending timeouts for meta messages and publish responses
                        self._debug('timeouts', _timeouts, org.cometd.JSON.toJSON(_timeouts));

                        if (!msg.data && msg.id && _timeouts[msg.id])
                        {
                            self._debug('timeout', _timeouts[msg.id]);
                            clearTimeout(_timeouts[msg.id]);
                            delete _timeouts[msg.id];
                        }

                        // check for disconnect
                        if ('/meta/disconnect' == msg.channel && msg.successful)
                        {
                            webSocket.close();
                        }
                    }

                    if (mc)
                    {
                        _metaConnectEnvelope.onSuccess(rcvdMessages);
                    }
                    else
                    {
                        _envelope.onSuccess(rcvdMessages);
                    }
                }
                else
                {
                    envelope.onFailure(webSocket, 'closed', null);
                }
            };
        }
    };

    that.reset = function()
    {
        _super.reset();
        if (_webSocket)
        {
            _webSocket.close();
        }
        _supportsWebSocket = true;
        _state = _WebSocket.CLOSED;
        _envelope = null;
        _metaConnectEnvelope = null;
    };

    return that;
};
