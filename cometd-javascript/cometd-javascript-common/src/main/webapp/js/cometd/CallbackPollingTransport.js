import { RequestTransport } from "./RequestTransport.js";
import { Transport } from "./Transport.js";

export function CallbackPollingTransport() {
  const _super = new RequestTransport();
  const _self = Transport.derive(_super);
  let jsonp = 0;

  _self.accept = (version, crossDomain, url) => true;

  _self.jsonpSend = (packet) => {
    const head = document.getElementsByTagName("head")[0];
    const script = document.createElement("script");

    const callbackName = "_cometd_jsonp_" + jsonp++;
    window[callbackName] = (responseText) => {
      head.removeChild(script);
      delete window[callbackName];
      packet.onSuccess(responseText);
    };

    let url = packet.url;
    url += url.indexOf("?") < 0 ? "?" : "&";
    url += "jsonp=" + callbackName;
    url += "&message=" + encodeURIComponent(packet.body);
    script.src = url;
    script.async = packet.sync !== true;
    script.type = "application/javascript";
    script.onerror = (e) => {
      packet.onError("jsonp " + e.type);
    };
    head.appendChild(script);
  };

  function _failTransportFn(envelope, request, x) {
    return () => {
      this.transportFailure(envelope, request, "error", x);
    };
  }

  _self.transportSend = function (envelope, request) {
    // Microsoft Internet Explorer has a 2083 URL max length
    // We must ensure that we stay within that length
    let start = 0;
    let length = envelope.messages.length;
    const lengths = [];
    while (length > 0) {
      // Encode the messages because all brackets, quotes, commas, colons, etc
      // present in the JSON will be URL encoded, taking many more characters
      const json = JSON.stringify(
        envelope.messages.slice(start, start + length)
      );
      const urlLength = envelope.url.length + encodeURI(json).length;

      const maxLength = this.getConfiguration().maxURILength;
      if (urlLength > maxLength) {
        if (length === 1) {
          const x =
            "Bayeux message too big (" +
            urlLength +
            " bytes, max is " +
            maxLength +
            ") " +
            "for transport " +
            this.getType();
          // Keep the semantic of calling callbacks asynchronously.
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

    let envelopeToSend = envelope;
    if (lengths.length > 1) {
      let begin = 0;
      let end = lengths[0];
      this._debug(
        "Transport",
        this.getType(),
        "split",
        envelope.messages.length,
        "messages into",
        lengths.join(" + ")
      );
      envelopeToSend = this._mixin(false, {}, envelope);
      envelopeToSend.messages = envelope.messages.slice(begin, end);
      envelopeToSend.onSuccess = envelope.onSuccess;
      envelopeToSend.onFailure = envelope.onFailure;

      for (let i = 1; i < lengths.length; ++i) {
        const nextEnvelope = this._mixin(false, {}, envelope);
        begin = end;
        end += lengths[i];
        nextEnvelope.messages = envelope.messages.slice(begin, end);
        nextEnvelope.onSuccess = envelope.onSuccess;
        nextEnvelope.onFailure = envelope.onFailure;
        this.send(nextEnvelope, request.metaConnect);
      }
    }

    this._debug(
      "Transport",
      this.getType(),
      "sending request",
      request.id,
      "envelope",
      envelopeToSend
    );

    try {
      let sameStack = true;
      this.jsonpSend({
        transport: this,
        url: envelopeToSend.url,
        sync: envelopeToSend.sync,
        headers: this.getConfiguration().requestHeaders,
        body: JSON.stringify(envelopeToSend.messages),
        onSuccess: (responses) => {
          let success = false;
          try {
            const received = this.convertToMessages(responses);
            if (received.length === 0) {
              this.transportFailure(envelopeToSend, request, {
                httpCode: 204,
              });
            } else {
              success = true;
              this.transportSuccess(envelopeToSend, request, received);
            }
          } catch (x) {
            this._debug(x);
            if (!success) {
              this.transportFailure(envelopeToSend, request, {
                exception: x,
              });
            }
          }
        },
        onError: (reason, exception) => {
          const failure = {
            reason: reason,
            exception: exception,
          };
          if (sameStack) {
            // Keep the semantic of calling callbacks asynchronously.
            this.setTimeout(() => {
              this.transportFailure(envelopeToSend, request, failure);
            }, 0);
          } else {
            this.transportFailure(envelopeToSend, request, failure);
          }
        },
      });
      sameStack = false;
      return true;
    } catch (xx) {
      // Keep the semantic of calling callbacks asynchronously.
      this.setTimeout(() => {
        this.transportFailure(envelopeToSend, request, {
          exception: xx,
        });
      }, 0);
      return false;
    }
  };

  return _self;
}
