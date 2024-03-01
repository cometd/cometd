import { Transport } from "./Transport.js";

export function WebSocketTransport() {
  const _super = new Transport();
  const _self = Transport.derive(_super);
  let _cometd;
  // By default WebSocket is supported
  let _webSocketSupported = true;
  // Whether we were able to establish a WebSocket connection
  let _webSocketConnected = false;
  let _stickyReconnect = true;
  // The context contains the envelopes that have been sent
  // and the timeouts for the messages that have been sent.
  let _context = null;
  let _connecting = null;
  let _connected = false;
  let _successCallback = null;

  _self.reset = (init) => {
    _super.reset(init);
    _webSocketSupported = true;
    if (init) {
      _webSocketConnected = false;
    }
    _stickyReconnect = true;
    if (init) {
      _context = null;
    }
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
    const messageIds = [];
    for (let i = 0; i < envelope.messages.length; ++i) {
      const message = envelope.messages[i];
      if (message.id) {
        messageIds.push(message.id);
      }
    }
    context.envelopes[messageIds.join(",")] = [envelope, metaConnect];
    this._debug(
      "Transport",
      this.getType(),
      "stored envelope, envelopes",
      context.envelopes
    );
  }

  function _removeEnvelope(context, messageIds) {
    let removed = false;
    const envelopes = context.envelopes;
    for (let j = 0; j < messageIds.length; ++j) {
      const id = messageIds[j];
      for (let key in envelopes) {
        if (envelopes.hasOwnProperty(key)) {
          const ids = key.split(",");
          const index = ids.indexOf(id);
          if (index >= 0) {
            removed = true;
            ids.splice(index, 1);
            const envelope = envelopes[key][0];
            const metaConnect = envelopes[key][1];
            delete envelopes[key];
            if (ids.length > 0) {
              envelopes[ids.join(",")] = [envelope, metaConnect];
            }
            break;
          }
        }
      }
    }
    if (removed) {
      this._debug(
        "Transport",
        this.getType(),
        "removed envelope, envelopes",
        envelopes
      );
    }
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
    const url = _cometd.getURL().replace(/^http/, "ws");
    this._debug("Transport", this.getType(), "connecting to URL", url);

    try {
      const protocol = _cometd.getConfiguration().protocol;
      context.webSocket = protocol
        ? new window.WebSocket(url, protocol)
        : new window.WebSocket(url);
      _connecting = context;
    } catch (x) {
      _webSocketSupported = false;
      this._debug("Exception while creating WebSocket object", x);
      throw x;
    }

    // By default use sticky reconnects.
    _stickyReconnect = _cometd.getConfiguration().stickyReconnect !== false;

    const connectTimeout = _cometd.getConfiguration().connectTimeout;
    if (connectTimeout > 0) {
      context.connectTimer = this.setTimeout(() => {
        _cometd._debug(
          "Transport",
          this.getType(),
          "timed out while connecting to URL",
          url,
          ":",
          connectTimeout,
          "ms"
        );
        // The connection was not opened, close anyway.
        _forceClose.call(this, context, {
          code: 1000,
          reason: "Connect Timeout",
        });
      }, connectTimeout);
    }

    const onopen = () => {
      _cometd._debug("WebSocket onopen", context);
      if (context.connectTimer) {
        this.clearTimeout(context.connectTimer);
      }

      if (_sameContext(context)) {
        _connecting = null;
        _context = context;
        _webSocketConnected = true;
        this.onOpen(context);
      } else {
        // We have a valid connection already, close this one.
        _cometd._warn(
          "Closing extra WebSocket connection",
          this,
          "active connection",
          _context
        );
        _forceClose.call(this, context, {
          code: 1000,
          reason: "Extra Connection",
        });
      }
    };

    // This callback is invoked when the server sends the close frame.
    // The close frame for a connection may arrive *after* another
    // connection has been opened, so we must make sure that actions
    // are performed only if it's the same connection.
    const onclose = (event) => {
      event = event || { code: 1000 };
      _cometd._debug(
        "WebSocket onclose",
        context,
        event,
        "connecting",
        _connecting,
        "current",
        _context
      );

      if (context.connectTimer) {
        this.clearTimeout(context.connectTimer);
      }

      this.onClose(context, event);
    };

    const onmessage = (wsMessage) => {
      _cometd._debug("WebSocket onmessage", wsMessage, context);
      this.onMessage(context, wsMessage);
    };

    context.webSocket.onopen = onopen;
    context.webSocket.onclose = onclose;
    context.webSocket.onerror = () => {
      // Clients should call onclose(), but if they do not we do it here for safety.
      onclose({ code: 1000, reason: "Error" });
    };
    context.webSocket.onmessage = onmessage;

    this._debug(
      "Transport",
      this.getType(),
      "configured callbacks on",
      context
    );
  }

  function _onTransportTimeout(context, message, delay) {
    const result = this._notifyTransportTimeout([message]);
    if (result > 0) {
      this._debug(
        "Transport",
        this.getType(),
        "extended waiting for message replies:",
        result,
        "ms"
      );
      context.timeouts[message.id] = this.setTimeout(() => {
        _onTransportTimeout.call(this, context, message, delay + result);
      }, result);
    } else {
      this._debug(
        "Transport",
        this.getType(),
        "expired waiting for message reply",
        message.id,
        ":",
        delay,
        "ms"
      );
      _forceClose.call(this, context, {
        code: 1000,
        reason: "Message Timeout",
      });
    }
  }

  function _webSocketSend(context, envelope, metaConnect) {
    let json;
    try {
      json = this.convertToJSON(envelope.messages);
    } catch (x) {
      this._debug("Transport", this.getType(), "exception:", x);
      const mIds = [];
      for (let j = 0; j < envelope.messages.length; ++j) {
        const m = envelope.messages[j];
        mIds.push(m.id);
      }
      _removeEnvelope.call(this, context, mIds);
      // Keep the semantic of calling callbacks asynchronously.
      this.setTimeout(() => {
        this._notifyFailure(envelope.onFailure, context, envelope.messages, {
          exception: x,
        });
      }, 0);
      return;
    }

    context.webSocket.send(json);
    this._debug(
      "Transport",
      this.getType(),
      "sent",
      envelope,
      "/meta/connect =",
      metaConnect
    );

    // Manage the timeout waiting for the response.
    let delay = this.getConfiguration().maxNetworkDelay;
    if (metaConnect) {
      delay += this.getAdvice().timeout;
      _connected = true;
    }

    const messageIds = [];
    for (let i = 0; i < envelope.messages.length; ++i) {
      const message = envelope.messages[i];
      if (message.id) {
        messageIds.push(message.id);
        context.timeouts[message.id] = this.setTimeout(() => {
          _onTransportTimeout.call(this, context, message, delay);
        }, delay);
      }
    }

    this._debug(
      "Transport",
      this.getType(),
      "started waiting for message replies",
      delay,
      "ms, messageIds:",
      messageIds,
      ", timeouts:",
      context.timeouts
    );
  }

  _self._notifySuccess = function (fn, messages) {
    fn.call(this, messages);
  };

  _self._notifyFailure = function (fn, context, messages, failure) {
    fn.call(this, context, messages, failure);
  };

  function _send(context, envelope, metaConnect) {
    try {
      if (context === null) {
        context = _connecting || {
          envelopes: {},
          timeouts: {},
        };
        _storeEnvelope.call(this, context, envelope, metaConnect);
        _websocketConnect.call(this, context);
      } else {
        _storeEnvelope.call(this, context, envelope, metaConnect);
        _webSocketSend.call(this, context, envelope, metaConnect);
      }
    } catch (x) {
      // Keep the semantic of calling callbacks asynchronously.
      this.setTimeout(() => {
        _forceClose.call(this, context, {
          code: 1000,
          reason: "Exception",
          exception: x,
        });
      }, 0);
    }
  }

  _self.onOpen = function (context) {
    const envelopes = context.envelopes;
    this._debug(
      "Transport",
      this.getType(),
      "opened",
      context,
      "pending messages",
      envelopes
    );
    for (let key in envelopes) {
      if (envelopes.hasOwnProperty(key)) {
        const element = envelopes[key];
        const envelope = element[0];
        const metaConnect = element[1];
        // Store the success callback, which is independent from the envelope,
        // so that it can be used to notify arrival of messages.
        _successCallback = envelope.onSuccess;
        _webSocketSend.call(this, context, envelope, metaConnect);
      }
    }
  };

  _self.onMessage = function (context, wsMessage) {
    this._debug(
      "Transport",
      this.getType(),
      "received websocket message",
      wsMessage,
      context
    );

    let close = false;
    const messages = this.convertToMessages(wsMessage.data);
    const messageIds = [];
    for (let i = 0; i < messages.length; ++i) {
      const message = messages[i];

      // Detect if the message is a response to a request we made.
      // If it's a meta message, for sure it's a response; otherwise it's
      // a publish message and publish responses don't have the data field.
      if (/^\/meta\//.test(message.channel) || message.data === undefined) {
        if (message.id) {
          messageIds.push(message.id);

          const timeout = context.timeouts[message.id];
          if (timeout) {
            this.clearTimeout(timeout);
            delete context.timeouts[message.id];
            this._debug(
              "Transport",
              this.getType(),
              "removed timeout for message",
              message.id,
              ", timeouts",
              context.timeouts
            );
          }
        }
      }

      if ("/meta/connect" === message.channel) {
        _connected = false;
      }
      if ("/meta/disconnect" === message.channel && !_connected) {
        close = true;
      }
    }

    // Remove the envelope corresponding to the messages.
    _removeEnvelope.call(this, context, messageIds);

    this._notifySuccess(_successCallback, messages);

    if (close) {
      this.webSocketClose(context, 1000, "Disconnect");
    }
  };

  _self.onClose = function (context, event) {
    this._debug("Transport", this.getType(), "closed", context, event);

    if (_sameContext(context)) {
      // Remember if we were able to connect.
      // This close event could be due to server shutdown,
      // and if it restarts we want to try websocket again.
      _webSocketSupported = _stickyReconnect && _webSocketConnected;
      _connecting = null;
      _context = null;
    }

    const timeouts = context.timeouts;
    context.timeouts = {};
    for (let id in timeouts) {
      if (timeouts.hasOwnProperty(id)) {
        this.clearTimeout(timeouts[id]);
      }
    }

    const envelopes = context.envelopes;
    context.envelopes = {};
    for (let key in envelopes) {
      if (envelopes.hasOwnProperty(key)) {
        const envelope = envelopes[key][0];
        const metaConnect = envelopes[key][1];
        if (metaConnect) {
          _connected = false;
        }
        const failure = {
          websocketCode: event.code,
          reason: event.reason,
        };
        if (event.exception) {
          failure.exception = event.exception;
        }
        this._notifyFailure(
          envelope.onFailure,
          context,
          envelope.messages,
          failure
        );
      }
    }
  };

  _self.registered = (type, cometd) => {
    _super.registered(type, cometd);
    _cometd = cometd;
  };

  _self.accept = function (version, crossDomain, url) {
    this._debug(
      "Transport",
      this.getType(),
      "accept, supported:",
      _webSocketSupported
    );
    // Using !! to return a boolean (and not the WebSocket object).
    return (
      _webSocketSupported &&
      !!window.WebSocket &&
      _cometd.websocketEnabled !== false
    );
  };

  _self.send = function (envelope, metaConnect) {
    this._debug(
      "Transport",
      this.getType(),
      "sending",
      envelope,
      "/meta/connect =",
      metaConnect
    );
    _send.call(this, _context, envelope, metaConnect);
  };

  _self.webSocketClose = function (context, code, reason) {
    try {
      if (context.webSocket) {
        context.webSocket.close(code, reason);
      }
    } catch (x) {
      this._debug(x);
    }
  };

  _self.abort = function () {
    _super.abort();
    _forceClose.call(this, _context, { code: 1000, reason: "Abort" });
    this.reset(true);
  };

  return _self;
}
