import { RequestTransport } from "./RequestTransport.js";
import { Transport } from "./Transport.js";

export function LongPollingTransport() {
  const _super = new RequestTransport();
  const _self = Transport.derive(_super);
  // By default, support cross domain
  let _supportsCrossDomain = true;

  _self.accept = (version, crossDomain, url) =>
    _supportsCrossDomain || !crossDomain;

  _self.newXMLHttpRequest = () => new window.XMLHttpRequest();

  function _copyContext(xhr) {
    try {
      // Copy external context, to be used in other environments.
      xhr.context = _self.context;
    } catch (e) {
      // May happen if XHR is wrapped by Object.seal(),
      // Object.freeze(), or Object.preventExtensions().
      _self._debug("Could not copy transport context into XHR", e);
    }
  }

  _self.xhrSend = (packet) => {
    const xhr = _self.newXMLHttpRequest();
    _copyContext(xhr);
    xhr.withCredentials = true;
    xhr.open("POST", packet.url, packet.sync !== true);
    const headers = packet.headers;
    if (headers) {
      for (let headerName in headers) {
        if (headers.hasOwnProperty(headerName)) {
          xhr.setRequestHeader(headerName, headers[headerName]);
        }
      }
    }
    xhr.setRequestHeader("Content-Type", "application/json;charset=UTF-8");
    xhr.onload = () => {
      if (xhr.status === 200) {
        packet.onSuccess(xhr.responseText);
      } else {
        packet.onError(xhr.statusText);
      }
    };
    xhr.onabort = xhr.onerror = () => {
      packet.onError(xhr.statusText);
    };
    xhr.send(packet.body);
    return xhr;
  };

  _self.transportSend = function (envelope, request) {
    this._debug(
      "Transport",
      this.getType(),
      "sending request",
      request.id,
      "envelope",
      envelope
    );

    try {
      let sameStack = true;
      request.xhr = this.xhrSend({
        transport: this,
        url: envelope.url,
        sync: envelope.sync,
        headers: this.getConfiguration().requestHeaders,
        body: this.convertToJSON(envelope.messages),
        onSuccess: (response) => {
          this._debug(
            "Transport",
            this.getType(),
            "received response",
            response
          );
          let success = false;
          try {
            const received = this.convertToMessages(response);
            if (received.length === 0) {
              _supportsCrossDomain = false;
              this.transportFailure(envelope, request, {
                httpCode: 204,
              });
            } else {
              success = true;
              this.transportSuccess(envelope, request, received);
            }
          } catch (x) {
            this._debug(x);
            if (!success) {
              _supportsCrossDomain = false;
              const failure = {
                exception: x,
              };
              failure.httpCode = this.xhrStatus(request.xhr);
              this.transportFailure(envelope, request, failure);
            }
          }
        },
        onError: (reason, exception) => {
          this._debug(
            "Transport",
            this.getType(),
            "received error",
            reason,
            exception
          );
          _supportsCrossDomain = false;
          const failure = {
            reason: reason,
            exception: exception,
          };
          failure.httpCode = this.xhrStatus(request.xhr);
          if (sameStack) {
            // Keep the semantic of calling callbacks asynchronously.
            this.setTimeout(() => {
              this.transportFailure(envelope, request, failure);
            }, 0);
          } else {
            this.transportFailure(envelope, request, failure);
          }
        },
      });
      sameStack = false;
      return true;
    } catch (x) {
      this._debug("Transport", this.getType(), "exception:", x);
      _supportsCrossDomain = false;
      // Keep the semantic of calling callbacks asynchronously.
      this.setTimeout(() => {
        this.transportFailure(envelope, request, {
          exception: x,
        });
      }, 0);
      return false;
    }
  };

  _self.reset = (init) => {
    _super.reset(init);
    _supportsCrossDomain = true;
  };

  return _self;
}
