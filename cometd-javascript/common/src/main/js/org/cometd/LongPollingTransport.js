;(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        // AMD Definition
        define(['./Transport', './RequestTransport'], factory);
    } else if (typeof require === 'function' && typeof exports === 'object') {
        // CommonJS
        module.exports = factory(require('./Transport'), require('./RequestTransport'));
    } else {
        // Global Definition

        // Namespaces for the cometd implementation
        root.org = root.org || {};
        root.org.cometd = org.cometd || {};

        var namespace = root.org.cometd;
        namespace.LongPollingTransport = factory(namespace.Transport, namespace.RequestTransport);
    }
}(this, function (Transport, RequestTransport) {
    'use strict';

    var LongPollingTransport = function() {
        var _super = new RequestTransport();
        var _self = Transport.derive(_super);
        // By default, support cross domain
        var _supportsCrossDomain = true;

        _self.accept = function(version, crossDomain, url) {
            return _supportsCrossDomain || !crossDomain;
        };

        _self.xhrSend = function(packet) {
            throw 'Abstract';
        };

        _self.transportSend = function(envelope, request) {
            this._debug('Transport', this.getType(), 'sending request', request.id, 'envelope', envelope);

            var self = this;
            try {
                var sameStack = true;
                request.xhr = this.xhrSend({
                    transport: this,
                    url: envelope.url,
                    sync: envelope.sync,
                    headers: this.getConfiguration().requestHeaders,
                    body: JSON.stringify(envelope.messages),
                    onSuccess: function(response) {
                        self._debug('Transport', self.getType(), 'received response', response);
                        var success = false;
                        try {
                            var received = self.convertToMessages(response);
                            if (received.length === 0) {
                                _supportsCrossDomain = false;
                                self.transportFailure(envelope, request, {
                                    httpCode: 204
                                });
                            } else {
                                success = true;
                                self.transportSuccess(envelope, request, received);
                            }
                        } catch (x) {
                            self._debug(x);
                            if (!success) {
                                _supportsCrossDomain = false;
                                var failure = {
                                    exception: x
                                };
                                failure.httpCode = self.xhrStatus(request.xhr);
                                self.transportFailure(envelope, request, failure);
                            }
                        }
                    },
                    onError: function(reason, exception) {
                        self._debug('Transport', self.getType(), 'received error', reason, exception);
                        _supportsCrossDomain = false;
                        var failure = {
                            reason: reason,
                            exception: exception
                        };
                        failure.httpCode = self.xhrStatus(request.xhr);
                        if (sameStack) {
                            // Keep the semantic of calling response callbacks asynchronously after the request
                            self.setTimeout(function() {
                                self.transportFailure(envelope, request, failure);
                            }, 0);
                        } else {
                            self.transportFailure(envelope, request, failure);
                        }
                    }
                });
                sameStack = false;
            } catch (x) {
                _supportsCrossDomain = false;
                // Keep the semantic of calling response callbacks asynchronously after the request
                this.setTimeout(function() {
                    self.transportFailure(envelope, request, {
                        exception: x
                    });
                }, 0);
            }
        };

        _self.reset = function(init) {
            _super.reset(init);
            _supportsCrossDomain = true;
        };

        return _self;
    };

    return LongPollingTransport;
}));
