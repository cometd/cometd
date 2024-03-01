import { Transport } from "./Transport.js";

/**
 * Base object with the common functionality for transports based on requests.
 * The key responsibility is to allow at most 2 outstanding requests to the server,
 * to avoid that requests are sent behind a long poll.
 * To achieve this, we have one reserved request for the long poll, and all other
 * requests are serialized one after the other.
 */
export function RequestTransport() {
  const _super = new Transport();
  const _self = Transport.derive(_super);
  let _requestIds = 0;
  let _metaConnectRequest = null;
  let _requests = [];
  let _envelopes = [];

  function _coalesceEnvelopes(envelope) {
    while (_envelopes.length > 0) {
      const envelopeAndRequest = _envelopes[0];
      const newEnvelope = envelopeAndRequest[0];
      const newRequest = envelopeAndRequest[1];
      if (
        newEnvelope.url === envelope.url &&
        newEnvelope.sync === envelope.sync
      ) {
        _envelopes.shift();
        envelope.messages = envelope.messages.concat(newEnvelope.messages);
        this._debug(
          "Coalesced",
          newEnvelope.messages.length,
          "messages from request",
          newRequest.id
        );
        continue;
      }
      break;
    }
  }

  function _onTransportTimeout(envelope, request, delay) {
    const result = this._notifyTransportTimeout(envelope.messages);
    if (result > 0) {
      this._debug(
        "Transport",
        this.getType(),
        "extended waiting for message replies of request",
        request.id,
        ":",
        result,
        "ms"
      );
      request.timeout = this.setTimeout(() => {
        _onTransportTimeout.call(this, envelope, request, delay + result);
      }, result);
    } else {
      request.expired = true;
      const errorMessage =
        "Transport " +
        this.getType() +
        " expired waiting for message replies of request " +
        request.id +
        ": " +
        delay +
        " ms";
      const failure = {
        reason: errorMessage,
      };
      const xhr = request.xhr;
      failure.httpCode = this.xhrStatus(xhr);
      this.abortXHR(xhr);
      this._debug(errorMessage);
      this.complete(request, false, request.metaConnect);
      envelope.onFailure(xhr, envelope.messages, failure);
    }
  }

  function _transportSend(envelope, request) {
    if (this.transportSend(envelope, request)) {
      request.expired = false;

      if (!envelope.sync) {
        let delay = this.getConfiguration().maxNetworkDelay;
        if (request.metaConnect === true) {
          delay += this.getAdvice().timeout;
        }

        this._debug(
          "Transport",
          this.getType(),
          "started waiting for message replies of request",
          request.id,
          ":",
          delay,
          "ms"
        );

        request.timeout = this.setTimeout(() => {
          _onTransportTimeout.call(this, envelope, request, delay);
        }, delay);
      }
    }
  }

  function _queueSend(envelope) {
    const requestId = ++_requestIds;
    const request = {
      id: requestId,
      metaConnect: false,
      envelope: envelope,
    };

    // Consider the /meta/connect requests which should always be present.
    if (_requests.length < this.getConfiguration().maxConnections - 1) {
      _requests.push(request);
      _transportSend.call(this, envelope, request);
    } else {
      this._debug(
        "Transport",
        this.getType(),
        "queueing request",
        requestId,
        "envelope",
        envelope
      );
      _envelopes.push([envelope, request]);
    }
  }

  function _metaConnectComplete(request) {
    const requestId = request.id;
    this._debug(
      "Transport",
      this.getType(),
      "/meta/connect complete, request",
      requestId
    );
    if (_metaConnectRequest !== null && _metaConnectRequest.id !== requestId) {
      throw "/meta/connect request mismatch, completing request " + requestId;
    }
    _metaConnectRequest = null;
  }

  function _complete(request, success) {
    const index = _requests.indexOf(request);
    // The index can be negative if the request has been aborted
    if (index >= 0) {
      _requests.splice(index, 1);
    }

    if (_envelopes.length > 0) {
      const envelopeAndRequest = _envelopes.shift();
      const nextEnvelope = envelopeAndRequest[0];
      const nextRequest = envelopeAndRequest[1];
      this._debug("Transport dequeued request", nextRequest.id);
      if (success) {
        if (this.getConfiguration().autoBatch) {
          _coalesceEnvelopes.call(this, nextEnvelope);
        }
        _queueSend.call(this, nextEnvelope);
        this._debug("Transport completed request", request.id, nextEnvelope);
      } else {
        // Keep the semantic of calling callbacks asynchronously.
        this.setTimeout(() => {
          this.complete(nextRequest, false, nextRequest.metaConnect);
          const failure = {
            reason: "Previous request failed",
          };
          const xhr = nextRequest.xhr;
          failure.httpCode = this.xhrStatus(xhr);
          nextEnvelope.onFailure(xhr, nextEnvelope.messages, failure);
        }, 0);
      }
    }
  }

  _self.complete = function (request, success, metaConnect) {
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
   * @return {boolean} whether the send succeeded
   */
  _self.transportSend = (envelope, request) => {
    throw "Abstract";
  };

  _self.transportSuccess = function (envelope, request, responses) {
    if (!request.expired) {
      this.clearTimeout(request.timeout);
      this._debug(
        "Transport",
        this.getType(),
        "cancelled waiting for message replies"
      );
      this.complete(request, true, request.metaConnect);
      if (responses && responses.length > 0) {
        envelope.onSuccess(responses);
      } else {
        envelope.onFailure(request.xhr, envelope.messages, {
          httpCode: 204,
        });
      }
    }
  };

  _self.transportFailure = function (envelope, request, failure) {
    if (!request.expired) {
      this.clearTimeout(request.timeout);
      this._debug(
        "Transport",
        this.getType(),
        "cancelled waiting for failed message replies"
      );
      this.complete(request, false, request.metaConnect);
      envelope.onFailure(request.xhr, envelope.messages, failure);
    }
  };

  function _metaConnectSend(envelope) {
    if (_metaConnectRequest !== null) {
      throw (
        "Concurrent /meta/connect requests not allowed, request id=" +
        _metaConnectRequest.id +
        " not yet completed"
      );
    }

    const requestId = ++_requestIds;
    this._debug(
      "Transport",
      this.getType(),
      "/meta/connect send, request",
      requestId,
      "envelope",
      envelope
    );
    const request = {
      id: requestId,
      metaConnect: true,
      envelope: envelope,
    };
    _transportSend.call(this, envelope, request);
    _metaConnectRequest = request;
  }

  _self.send = function (envelope, metaConnect) {
    if (metaConnect) {
      _metaConnectSend.call(this, envelope);
    } else {
      _queueSend.call(this, envelope);
    }
  };

  _self.abort = function () {
    _super.abort();
    for (let i = 0; i < _requests.length; ++i) {
      const request = _requests[i];
      if (request) {
        this._debug("Aborting request", request);
        if (!this.abortXHR(request.xhr)) {
          this.transportFailure(request.envelope, request, { reason: "abort" });
        }
      }
    }
    const metaConnectRequest = _metaConnectRequest;
    if (metaConnectRequest) {
      this._debug("Aborting /meta/connect request", metaConnectRequest);
      if (!this.abortXHR(metaConnectRequest.xhr)) {
        this.transportFailure(metaConnectRequest.envelope, metaConnectRequest, {
          reason: "abort",
        });
      }
    }
    this.reset(true);
  };

  _self.reset = (init) => {
    _super.reset(init);
    _metaConnectRequest = null;
    _requests = [];
    _envelopes = [];
  };

  _self.abortXHR = function (xhr) {
    if (xhr) {
      try {
        const state = xhr.readyState;
        xhr.abort();
        return state !== window.XMLHttpRequest.UNSENT;
      } catch (x) {
        this._debug(x);
      }
    }
    return false;
  };

  _self.xhrStatus = function (xhr) {
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
}
