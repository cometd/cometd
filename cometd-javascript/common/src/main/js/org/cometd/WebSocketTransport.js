org.cometd.WebSocketTransport = function() {
    var _super = new org.cometd.Transport();
    var _self = org.cometd.Transport.derive(_super);
    var _cometd;
    // By default WebSocket is supported
    var _webSocketSupported = true;
    // Whether we were able to establish a WebSocket connection
    var _webSocketConnected = false;
    var _stickyReconnect = true;
    // The context contains the envelopes that have been sent
    // and the timeouts for the messages that have been sent.
    var _context = null;
    var _connecting = null;
    var _connected = false;
    var _successCallback = null;

    _self.reset = function() {
        _super.reset();
        _webSocketSupported = true;
        _webSocketConnected = false;
        _stickyReconnect = true;
        _context = null;
        _connecting = null;
        _connected = false;
    };

    function _forceClose(context, event) {
        if (context) {
            this.webSocketClose(context, event.code, event.reason);
            // Force immediate failure of pending messages to trigger reconnect.
            // This is needed because the server may not reply to our close()
            // and therefore the onclose function is never called.
            this.onClose(context, event);
        }
    }

    function _sameContext(context) {
        return context === _connecting || context === _context;
    }

    function _storeEnvelope(context, envelope, metaConnect) {
        var messageIds = [];
        for (var i = 0; i < envelope.messages.length; ++i) {
            var message = envelope.messages[i];
            if (message.id) {
                messageIds.push(message.id);
            }
        }
        context.envelopes[messageIds.join(',')] = [envelope, metaConnect];
        this._debug('Transport', this.getType(), 'stored envelope, envelopes', context.envelopes);
    }

    function _websocketConnect(context) {
        // We may have multiple attempts to open a WebSocket
        // connection, for example a /meta/connect request that
        // may take time, along with a user-triggered publish.
        // Early return if we are already connecting.
        if (_connecting) {
            return;
        }

        // Mangle the URL, changing the scheme from 'http' to 'ws'.
        var url = _cometd.getURL().replace(/^http/, 'ws');
        this._debug('Transport', this.getType(), 'connecting to URL', url);

        try {
            var protocol = _cometd.getConfiguration().protocol;
            context.webSocket = protocol ? new org.cometd.WebSocket(url, protocol) : new org.cometd.WebSocket(url);
            _connecting = context;
        } catch (x) {
            _webSocketSupported = false;
            this._debug('Exception while creating WebSocket object', x);
            throw x;
        }

        // By default use sticky reconnects.
        _stickyReconnect = _cometd.getConfiguration().stickyReconnect !== false;

        var self = this;
        var connectTimeout = _cometd.getConfiguration().connectTimeout;
        if (connectTimeout > 0) {
            context.connectTimer = this.setTimeout(function() {
                _cometd._debug('Transport', self.getType(), 'timed out while connecting to URL', url, ':', connectTimeout, 'ms');
                // The connection was not opened, close anyway.
                _forceClose.call(self, context, {code: 1000, reason: 'Connect Timeout'});
            }, connectTimeout);
        }

        var onopen = function() {
            _cometd._debug('WebSocket onopen', context);
            if (context.connectTimer) {
                self.clearTimeout(context.connectTimer);
            }

            if (_sameContext(context)) {
                _connecting = null;
                _context = context;
                _webSocketConnected = true;
                self.onOpen(context);
            } else {
                // We have a valid connection already, close this one.
                _cometd._warn('Closing extra WebSocket connection', this, 'active connection', _context);
                _forceClose.call(self, context, {code: 1000, reason: 'Extra Connection'});
            }
        };

        // This callback is invoked when the server sends the close frame.
        // The close frame for a connection may arrive *after* another
        // connection has been opened, so we must make sure that actions
        // are performed only if it's the same connection.
        var onclose = function(event) {
            event = event || {code: 1000};
            _cometd._debug('WebSocket onclose', context, event, 'connecting', _connecting, 'current', _context);

            if (context.connectTimer) {
                self.clearTimeout(context.connectTimer);
            }

            self.onClose(context, event);
        };

        var onmessage = function(wsMessage) {
            _cometd._debug('WebSocket onmessage', wsMessage, context);
            self.onMessage(context, wsMessage);
        };

        context.webSocket.onopen = onopen;
        context.webSocket.onclose = onclose;
        context.webSocket.onerror = function() {
            // Clients should call onclose(), but if they do not we do it here for safety.
            onclose({code: 1000, reason: 'Error'});
        };
        context.webSocket.onmessage = onmessage;

        this._debug('Transport', this.getType(), 'configured callbacks on', context);
    }

    function _webSocketSend(context, envelope, metaConnect) {
        var json = org.cometd.JSON.toJSON(envelope.messages);
        context.webSocket.send(json);
        this._debug('Transport', this.getType(), 'sent', envelope, 'metaConnect =', metaConnect);

        // Manage the timeout waiting for the response.
        var maxDelay = this.getConfiguration().maxNetworkDelay;
        var delay = maxDelay;
        if (metaConnect) {
            delay += this.getAdvice().timeout;
            _connected = true;
        }

        var self = this;
        var messageIds = [];
        for (var i = 0; i < envelope.messages.length; ++i) {
            (function() {
                var message = envelope.messages[i];
                if (message.id) {
                    messageIds.push(message.id);
                    context.timeouts[message.id] = self.setTimeout(function() {
                        _cometd._debug('Transport', self.getType(), 'timing out message', message.id, 'after', delay, 'on', context);
                        _forceClose.call(self, context, {code: 1000, reason: 'Message Timeout'});
                    }, delay);
                }
            })();
        }

        this._debug('Transport', this.getType(), 'waiting at most', delay, 'ms for messages', messageIds, 'maxNetworkDelay', maxDelay, ', timeouts:', context.timeouts);
    }

    _self._notifySuccess = function(fn, messages) {
        fn.call(this, messages);
    };

    _self._notifyFailure = function(fn, context, messages, failure) {
        fn.call(this, context, messages, failure);
    };

    function _send(context, envelope, metaConnect) {
        try {
            if (context === null) {
                context = _connecting || {
                        envelopes: {},
                        timeouts: {}
                    };
                _storeEnvelope.call(this, context, envelope, metaConnect);
                _websocketConnect.call(this, context);
            } else {
                _storeEnvelope.call(this, context, envelope, metaConnect);
                _webSocketSend.call(this, context, envelope, metaConnect);
            }
        } catch (x) {
            // Keep the semantic of calling response callbacks asynchronously after the request.
            var self = this;
            this.setTimeout(function() {
                _forceClose.call(self, context, {
                    code: 1000,
                    reason: 'Exception',
                    exception: x
                });
            }, 0);
        }
    }

    _self.onOpen = function(context) {
        var envelopes = context.envelopes;
        this._debug('Transport', this.getType(), 'opened', context, 'pending messages', envelopes);
        for (var key in envelopes) {
            if (envelopes.hasOwnProperty(key)) {
                var element = envelopes[key];
                var envelope = element[0];
                var metaConnect = element[1];
                // Store the success callback, which is independent from the envelope,
                // so that it can be used to notify arrival of messages.
                _successCallback = envelope.onSuccess;
                _webSocketSend.call(this, context, envelope, metaConnect);
            }
        }
    };

    _self.onMessage = function(context, wsMessage) {
        this._debug('Transport', this.getType(), 'received websocket message', wsMessage, context);

        var close = false;
        var messages = this.convertToMessages(wsMessage.data);
        var messageIds = [];
        for (var i = 0; i < messages.length; ++i) {
            var message = messages[i];

            // Detect if the message is a response to a request we made.
            // If it's a meta message, for sure it's a response; otherwise it's
            // a publish message and publish responses don't have the data field.
            if (/^\/meta\//.test(message.channel) || message.data === undefined) {
                if (message.id) {
                    messageIds.push(message.id);

                    var timeout = context.timeouts[message.id];
                    if (timeout) {
                        this.clearTimeout(timeout);
                        delete context.timeouts[message.id];
                        this._debug('Transport', this.getType(), 'removed timeout for message', message.id, ', timeouts', context.timeouts);
                    }
                }
            }

            if ('/meta/connect' === message.channel) {
                _connected = false;
            }
            if ('/meta/disconnect' === message.channel && !_connected) {
                close = true;
            }
        }

        // Remove the envelope corresponding to the messages.
        var removed = false;
        var envelopes = context.envelopes;
        for (var j = 0; j < messageIds.length; ++j) {
            var id = messageIds[j];
            for (var key in envelopes) {
                if (envelopes.hasOwnProperty(key)) {
                    var ids = key.split(',');
                    var index = org.cometd.Utils.inArray(id, ids);
                    if (index >= 0) {
                        removed = true;
                        ids.splice(index, 1);
                        var envelope = envelopes[key][0];
                        var metaConnect = envelopes[key][1];
                        delete envelopes[key];
                        if (ids.length > 0) {
                            envelopes[ids.join(',')] = [envelope, metaConnect];
                        }
                        break;
                    }
                }
            }
        }
        if (removed) {
            this._debug('Transport', this.getType(), 'removed envelope, envelopes', envelopes);
        }

        this._notifySuccess(_successCallback, messages);

        if (close) {
            this.webSocketClose(context, 1000, 'Disconnect');
        }
    };

    _self.onClose = function(context, event) {
        this._debug('Transport', this.getType(), 'closed', context, event);

        if (_sameContext(context)) {
            // Remember if we were able to connect.
            // This close event could be due to server shutdown,
            // and if it restarts we want to try websocket again.
            _webSocketSupported = _stickyReconnect && _webSocketConnected;
            _connecting = null;
            _context = null;
        }

        var timeouts = context.timeouts;
        context.timeouts = {};
        for (var id in timeouts) {
            if (timeouts.hasOwnProperty(id)) {
                this.clearTimeout(timeouts[id]);
            }
        }

        var envelopes = context.envelopes;
        context.envelopes = {};
        for (var key in envelopes) {
            if (envelopes.hasOwnProperty(key)) {
                var envelope = envelopes[key][0];
                var metaConnect = envelopes[key][1];
                if (metaConnect) {
                    _connected = false;
                }
                var failure = {
                    websocketCode: event.code,
                    reason: event.reason
                };
                if (event.exception) {
                    failure.exception = event.exception;
                }
                this._notifyFailure(envelope.onFailure, context, envelope.messages, failure);
            }
        }
    };

    _self.registered = function(type, cometd) {
        _super.registered(type, cometd);
        _cometd = cometd;
    };

    _self.accept = function(version, crossDomain, url) {
        this._debug('Transport', this.getType(), 'accept, supported:', _webSocketSupported);
        // Using !! to return a boolean (and not the WebSocket object).
        return _webSocketSupported && !!org.cometd.WebSocket && _cometd.websocketEnabled !== false;
    };

    _self.send = function(envelope, metaConnect) {
        this._debug('Transport', this.getType(), 'sending', envelope, 'metaConnect =', metaConnect);
        _send.call(this, _context, envelope, metaConnect);
    };

    _self.webSocketClose = function(context, code, reason) {
        try {
            if (context.webSocket) {
                context.webSocket.close(code, reason);
            }
        } catch (x) {
            this._debug(x);
        }
    };

    _self.abort = function() {
        _super.abort();
        _forceClose.call(this, _context, {code: 1000, reason: 'Abort'});
        this.reset();
    };

    return _self;
};
