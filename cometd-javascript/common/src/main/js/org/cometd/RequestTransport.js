;(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        // AMD Definition
        define(['./Utils', './Transport'], factory);
    } else if (typeof require === 'function' && typeof exports === 'object') {
        // CommonJS
        module.exports = factory(require('./Utils'), require('./Transport'));
    } else {
        // Global Definition

        // Namespaces for the cometd implementation
        root.org = root.org || {};
        root.org.cometd = org.cometd || {};

        var namespace = root.org.cometd;
        namespace.RequestTransport = factory(namespace.Utils, namespace.Transport);
    }
}(this, function (Utils, Transport) {
    'use strict';

    /**
     * Base object with the common functionality for transports based on requests.
     * The key responsibility is to allow at most 2 outstanding requests to the server,
     * to avoid that requests are sent behind a long poll.
     * To achieve this, we have one reserved request for the long poll, and all other
     * requests are serialized one after the other.
     */
    var RequestTransport = function() {
        var _super = new Transport();
        var _self = Transport.derive(_super);
        var _requestIds = 0;
        var _metaConnectRequest = null;
        var _requests = [];
        var _envelopes = [];

        function _coalesceEnvelopes(envelope) {
            while (_envelopes.length > 0) {
                var envelopeAndRequest = _envelopes[0];
                var newEnvelope = envelopeAndRequest[0];
                var newRequest = envelopeAndRequest[1];
                if (newEnvelope.url === envelope.url &&
                    newEnvelope.sync === envelope.sync) {
                    _envelopes.shift();
                    envelope.messages = envelope.messages.concat(newEnvelope.messages);
                    this._debug('Coalesced', newEnvelope.messages.length, 'messages from request', newRequest.id);
                    continue;
                }
                break;
            }
        }

        function _transportSend(envelope, request) {
            this.transportSend(envelope, request);
            request.expired = false;

            if (!envelope.sync) {
                var maxDelay = this.getConfiguration().maxNetworkDelay;
                var delay = maxDelay;
                if (request.metaConnect === true) {
                    delay += this.getAdvice().timeout;
                }

                this._debug('Transport', this.getType(), 'waiting at most', delay, 'ms for the response, maxNetworkDelay', maxDelay);

                var self = this;
                request.timeout = this.setTimeout(function() {
                    request.expired = true;
                    var errorMessage = 'Request ' + request.id + ' of transport ' + self.getType() + ' exceeded ' + delay + ' ms max network delay';
                    var failure = {
                        reason: errorMessage
                    };
                    var xhr = request.xhr;
                    failure.httpCode = self.xhrStatus(xhr);
                    self.abortXHR(xhr);
                    self._debug(errorMessage);
                    self.complete(request, false, request.metaConnect);
                    envelope.onFailure(xhr, envelope.messages, failure);
                }, delay);
            }
        }

        function _queueSend(envelope) {
            var requestId = ++_requestIds;
            var request = {
                id: requestId,
                metaConnect: false,
                envelope: envelope
            };

            // Consider the metaConnect requests which should always be present
            if (_requests.length < this.getConfiguration().maxConnections - 1) {
                _requests.push(request);
                _transportSend.call(this, envelope, request);
            } else {
                this._debug('Transport', this.getType(), 'queueing request', requestId, 'envelope', envelope);
                _envelopes.push([envelope, request]);
            }
        }

        function _metaConnectComplete(request) {
            var requestId = request.id;
            this._debug('Transport', this.getType(), 'metaConnect complete, request', requestId);
            if (_metaConnectRequest !== null && _metaConnectRequest.id !== requestId) {
                throw 'Longpoll request mismatch, completing request ' + requestId;
            }

            // Reset metaConnect request
            _metaConnectRequest = null;
        }

        function _complete(request, success) {
            var index = Utils.inArray(request, _requests);
            // The index can be negative if the request has been aborted
            if (index >= 0) {
                _requests.splice(index, 1);
            }

            if (_envelopes.length > 0) {
                var envelopeAndRequest = _envelopes.shift();
                var nextEnvelope = envelopeAndRequest[0];
                var nextRequest = envelopeAndRequest[1];
                this._debug('Transport dequeued request', nextRequest.id);
                if (success) {
                    if (this.getConfiguration().autoBatch) {
                        _coalesceEnvelopes.call(this, nextEnvelope);
                    }
                    _queueSend.call(this, nextEnvelope);
                    this._debug('Transport completed request', request.id, nextEnvelope);
                } else {
                    // Keep the semantic of calling response callbacks asynchronously after the request
                    var self = this;
                    this.setTimeout(function() {
                        self.complete(nextRequest, false, nextRequest.metaConnect);
                        var failure = {
                            reason: 'Previous request failed'
                        };
                        var xhr = nextRequest.xhr;
                        failure.httpCode = self.xhrStatus(xhr);
                        nextEnvelope.onFailure(xhr, nextEnvelope.messages, failure);
                    }, 0);
                }
            }
        }

        _self.complete = function(request, success, metaConnect) {
            if (metaConnect) {
                _metaConnectComplete.call(this, request);
            } else {
                _complete.call(this, request, success);
            }
        };

        /**
         * Performs the actual send depending on the transport type details.
         * @param envelope the envelope to send
         * @param request the request information
         */
        _self.transportSend = function(envelope, request) {
            throw 'Abstract';
        };

        _self.transportSuccess = function(envelope, request, responses) {
            if (!request.expired) {
                this.clearTimeout(request.timeout);
                this.complete(request, true, request.metaConnect);
                if (responses && responses.length > 0) {
                    envelope.onSuccess(responses);
                } else {
                    envelope.onFailure(request.xhr, envelope.messages, {
                        httpCode: 204
                    });
                }
            }
        };

        _self.transportFailure = function(envelope, request, failure) {
            if (!request.expired) {
                this.clearTimeout(request.timeout);
                this.complete(request, false, request.metaConnect);
                envelope.onFailure(request.xhr, envelope.messages, failure);
            }
        };

        function _metaConnectSend(envelope) {
            if (_metaConnectRequest !== null) {
                throw 'Concurrent metaConnect requests not allowed, request id=' + _metaConnectRequest.id + ' not yet completed';
            }

            var requestId = ++_requestIds;
            this._debug('Transport', this.getType(), 'metaConnect send, request', requestId, 'envelope', envelope);
            var request = {
                id: requestId,
                metaConnect: true,
                envelope: envelope
            };
            _transportSend.call(this, envelope, request);
            _metaConnectRequest = request;
        }

        _self.send = function(envelope, metaConnect) {
            if (metaConnect) {
                _metaConnectSend.call(this, envelope);
            } else {
                _queueSend.call(this, envelope);
            }
        };

        _self.abort = function() {
            _super.abort();
            for (var i = 0; i < _requests.length; ++i) {
                var request = _requests[i];
                if (request) {
                    this._debug('Aborting request', request);
                    if (!this.abortXHR(request.xhr)) {
                        this.transportFailure(request.envelope, request, {reason: 'abort'});
                    }
                }
            }
            if (_metaConnectRequest) {
                this._debug('Aborting metaConnect request', _metaConnectRequest);
                if (!this.abortXHR(_metaConnectRequest.xhr)) {
                    this.transportFailure(_metaConnectRequest.envelope, _metaConnectRequest, {reason: 'abort'});
                }
            }
            this.reset(true);
        };

        _self.reset = function(init) {
            _super.reset(init);
            _metaConnectRequest = null;
            _requests = [];
            _envelopes = [];
        };

        _self.abortXHR = function(xhr) {
            if (xhr) {
                try {
                    var state = xhr.readyState;
                    xhr.abort();
                    return state !== XMLHttpRequest.UNSENT;
                } catch (x) {
                    this._debug(x);
                }
            }
            return false;
        };

        _self.xhrStatus = function(xhr) {
            if (xhr) {
                try {
                    return xhr.status;
                } catch (x) {
                    this._debug(x);
                }
            }
            return -1;
        };

        return _self;
    };

    return RequestTransport;
}));
