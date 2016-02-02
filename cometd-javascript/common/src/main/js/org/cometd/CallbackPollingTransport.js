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
        namespace.CallbackPollingTransport = factory(namespace.Transport, namespace.RequestTransport);
    }
}(this, function (Transport, RequestTransport) {
    'use strict';

    var CallbackPollingTransport = function() {
        var _super = new RequestTransport();
        var _self = Transport.derive(_super);

        _self.accept = function(version, crossDomain, url) {
            return true;
        };

        _self.jsonpSend = function(packet) {
            throw 'Abstract';
        };

        function _failTransportFn(envelope, request, x) {
            var self = this;
            return function() {
                self.transportFailure(envelope, request, 'error', x);
            };
        }

        _self.transportSend = function(envelope, request) {
            var self = this;

            // Microsoft Internet Explorer has a 2083 URL max length
            // We must ensure that we stay within that length
            var start = 0;
            var length = envelope.messages.length;
            var lengths = [];
            while (length > 0) {
                // Encode the messages because all brackets, quotes, commas, colons, etc
                // present in the JSON will be URL encoded, taking many more characters
                var json = JSON.stringify(envelope.messages.slice(start, start + length));
                var urlLength = envelope.url.length + encodeURI(json).length;

                var maxLength = this.getConfiguration().maxURILength;
                if (urlLength > maxLength) {
                    if (length === 1) {
                        var x = 'Bayeux message too big (' + urlLength + ' bytes, max is ' + maxLength + ') ' +
                            'for transport ' + this.getType();
                        // Keep the semantic of calling response callbacks asynchronously after the request
                        this.setTimeout(_failTransportFn.call(this, envelope, request, x), 0);
                        return;
                    }

                    --length;
                    continue;
                }

                lengths.push(length);
                start += length;
                length = envelope.messages.length - start;
            }

            // Here we are sure that the messages can be sent within the URL limit

            var envelopeToSend = envelope;
            if (lengths.length > 1) {
                var begin = 0;
                var end = lengths[0];
                this._debug('Transport', this.getType(), 'split', envelope.messages.length, 'messages into', lengths.join(' + '));
                envelopeToSend = this._mixin(false, {}, envelope);
                envelopeToSend.messages = envelope.messages.slice(begin, end);
                envelopeToSend.onSuccess = envelope.onSuccess;
                envelopeToSend.onFailure = envelope.onFailure;

                for (var i = 1; i < lengths.length; ++i) {
                    var nextEnvelope = this._mixin(false, {}, envelope);
                    begin = end;
                    end += lengths[i];
                    nextEnvelope.messages = envelope.messages.slice(begin, end);
                    nextEnvelope.onSuccess = envelope.onSuccess;
                    nextEnvelope.onFailure = envelope.onFailure;
                    this.send(nextEnvelope, request.metaConnect);
                }
            }

            this._debug('Transport', this.getType(), 'sending request', request.id, 'envelope', envelopeToSend);

            try {
                var sameStack = true;
                this.jsonpSend({
                    transport: this,
                    url: envelopeToSend.url,
                    sync: envelopeToSend.sync,
                    headers: this.getConfiguration().requestHeaders,
                    body: JSON.stringify(envelopeToSend.messages),
                    onSuccess: function(responses) {
                        var success = false;
                        try {
                            var received = self.convertToMessages(responses);
                            if (received.length === 0) {
                                self.transportFailure(envelopeToSend, request, {
                                    httpCode: 204
                                });
                            } else {
                                success = true;
                                self.transportSuccess(envelopeToSend, request, received);
                            }
                        } catch (x) {
                            self._debug(x);
                            if (!success) {
                                self.transportFailure(envelopeToSend, request, {
                                    exception: x
                                });
                            }
                        }
                    },
                    onError: function(reason, exception) {
                        var failure = {
                            reason: reason,
                            exception: exception
                        };
                        if (sameStack) {
                            // Keep the semantic of calling response callbacks asynchronously after the request
                            self.setTimeout(function() {
                                self.transportFailure(envelopeToSend, request, failure);
                            }, 0);
                        } else {
                            self.transportFailure(envelopeToSend, request, failure);
                        }
                    }
                });
                sameStack = false;
            } catch (xx) {
                // Keep the semantic of calling response callbacks asynchronously after the request
                this.setTimeout(function() {
                    self.transportFailure(envelopeToSend, request, {
                        exception: xx
                    });
                }, 0);
            }
        };

        return _self;
    };

    return CallbackPollingTransport;
}));
