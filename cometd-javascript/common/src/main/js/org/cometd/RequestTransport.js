/**
 * Base object with the common functionality for transports based on requests.
 * The key responsibility is to allow at most 2 outstanding requests to the server,
 * to avoid that requests are sent behind a long poll.
 * To achieve this, we have one reserved request for the long poll, and all other
 * requests are serialized one after the other.
 */
org.cometd.RequestTransport = function()
{
    var _super = new org.cometd.Transport();
    var _self = org.cometd.Transport.derive(_super);
    var _requestIds = 0;
    var _metaConnectRequest = null;
    var _requests = [];
    var _envelopes = [];

    function _coalesceEnvelopes(envelope)
    {
        while (_envelopes.length > 0)
        {
            var envelopeAndRequest = _envelopes[0];
            var newEnvelope = envelopeAndRequest[0];
            var newRequest = envelopeAndRequest[1];
            if (newEnvelope.url === envelope.url &&
                    newEnvelope.sync === envelope.sync)
            {
                _envelopes.shift();
                envelope.messages = envelope.messages.concat(newEnvelope.messages);
                this._debug('Coalesced', newEnvelope.messages.length, 'messages from request', newRequest.id);
                continue;
            }
            break;
        }
    }

    function _transportSend(envelope, request)
    {
        this.transportSend(envelope, request);
        request.expired = false;

        if (!envelope.sync)
        {
            var maxDelay = this.getConfiguration().maxNetworkDelay;
            var delay = maxDelay;
            if (request.metaConnect === true)
            {
                delay += this.getAdvice().timeout;
            }

            this._debug('Transport', this.getType(), 'waiting at most', delay, 'ms for the response, maxNetworkDelay', maxDelay);

            var self = this;
            request.timeout = this.setTimeout(function()
            {
                request.expired = true;
                if (request.xhr)
                {
                    request.xhr.abort();
                }
                var errorMessage = 'Request ' + request.id + ' of transport ' + self.getType() + ' exceeded ' + delay + ' ms max network delay';
                self._debug(errorMessage);
                self.complete(request, false, request.metaConnect);
                envelope.onFailure(request.xhr, envelope.messages, 'timeout', errorMessage);
            }, delay);
        }
    }

    function _queueSend(envelope)
    {
        var requestId = ++_requestIds;
        var request = {
            id: requestId,
            metaConnect: false
        };

        // Consider the metaConnect requests which should always be present
        if (_requests.length < this.getConfiguration().maxConnections - 1)
        {
            _requests.push(request);
            _transportSend.call(this, envelope, request);
        }
        else
        {
            this._debug('Transport', this.getType(), 'queueing request', requestId, 'envelope', envelope);
            _envelopes.push([envelope, request]);
        }
    }

    function _metaConnectComplete(request)
    {
        var requestId = request.id;
        this._debug('Transport', this.getType(), 'metaConnect complete, request', requestId);
        if (_metaConnectRequest !== null && _metaConnectRequest.id !== requestId)
        {
            throw 'Longpoll request mismatch, completing request ' + requestId;
        }

        // Reset metaConnect request
        _metaConnectRequest = null;
    }

    function _complete(request, success)
    {
        var index = org.cometd.Utils.inArray(request, _requests);
        // The index can be negative if the request has been aborted
        if (index >= 0)
        {
            _requests.splice(index, 1);
        }

        if (_envelopes.length > 0)
        {
            var envelopeAndRequest = _envelopes.shift();
            var nextEnvelope = envelopeAndRequest[0];
            var nextRequest = envelopeAndRequest[1];
            this._debug('Transport dequeued request', nextRequest.id);
            if (success)
            {
                if (this.getConfiguration().autoBatch)
                {
                    _coalesceEnvelopes.call(this, nextEnvelope);
                }
                _queueSend.call(this, nextEnvelope);
                this._debug('Transport completed request', request.id, nextEnvelope);
            }
            else
            {
                // Keep the semantic of calling response callbacks asynchronously after the request
                var self = this;
                this.setTimeout(function()
                {
                    self.complete(nextRequest, false, nextRequest.metaConnect);
                    nextEnvelope.onFailure(nextRequest.xhr, nextEnvelope.messages, 'error', 'Previous request failed');
                }, 0);
            }
        }
    }

    _self.complete = function(request, success, metaConnect)
    {
        if (metaConnect)
        {
            _metaConnectComplete.call(this, request);
        }
        else
        {
            _complete.call(this, request, success);
        }
    };

    /**
     * Performs the actual send depending on the transport type details.
     * @param envelope the envelope to send
     * @param request the request information
     */
    _self.transportSend = function(envelope, request)
    {
        throw 'Abstract';
    };

    _self.transportSuccess = function(envelope, request, responses)
    {
        if (!request.expired)
        {
            this.clearTimeout(request.timeout);
            this.complete(request, true, request.metaConnect);
            if (responses && responses.length > 0)
            {
                envelope.onSuccess(responses);
            }
            else
            {
                envelope.onFailure(request.xhr, envelope.messages, 'Empty HTTP response');
            }
        }
    };

    _self.transportFailure = function(envelope, request, reason, exception)
    {
        if (!request.expired)
        {
            this.clearTimeout(request.timeout);
            this.complete(request, false, request.metaConnect);
            envelope.onFailure(request.xhr, envelope.messages, reason, exception);
        }
    };

    function _metaConnectSend(envelope)
    {
        if (_metaConnectRequest !== null)
        {
            throw 'Concurrent metaConnect requests not allowed, request id=' + _metaConnectRequest.id + ' not yet completed';
        }

        var requestId = ++_requestIds;
        this._debug('Transport', this.getType(), 'metaConnect send, request', requestId, 'envelope', envelope);
        var request = {
            id: requestId,
            metaConnect: true
        };
        _transportSend.call(this, envelope, request);
        _metaConnectRequest = request;
    }

    _self.send = function(envelope, metaConnect)
    {
        if (metaConnect)
        {
            _metaConnectSend.call(this, envelope);
        }
        else
        {
            _queueSend.call(this, envelope);
        }
    };

    _self.abort = function()
    {
        _super.abort();
        for (var i = 0; i < _requests.length; ++i)
        {
            var request = _requests[i];
            this._debug('Aborting request', request);
            if (request.xhr)
            {
                request.xhr.abort();
            }
        }
        if (_metaConnectRequest)
        {
            this._debug('Aborting metaConnect request', _metaConnectRequest);
            if (_metaConnectRequest.xhr)
            {
                _metaConnectRequest.xhr.abort();
            }
        }
        this.reset();
    };

    _self.reset = function()
    {
        _super.reset();
        _metaConnectRequest = null;
        _requests = [];
        _envelopes = [];
    };

    return _self;
};
