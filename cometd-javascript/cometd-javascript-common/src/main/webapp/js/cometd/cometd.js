import { TransportRegistry } from "./TransportRegistry.js";
import { Utils } from "./Utils.js";
import { CallbackPollingTransport } from "./CallbackPollingTransport.js";
import { LongPollingTransport } from "./LongPollingTransport.js";
import { WebSocketTransport } from "./WebSocketTransport.js";

/**
 * Browsers may throttle the Window scheduler,
 * so we may replace it with a Worker scheduler.
 */
function Scheduler() {
  let _ids = 0;
  const _tasks = {};
  this.register = (funktion) => {
    const id = ++_ids;
    _tasks[id] = funktion;
    return id;
  };
  this.unregister = (id) => {
    const funktion = _tasks[id];
    delete _tasks[id];
    return funktion;
  };
  this.setTimeout = (funktion, delay) => window.setTimeout(funktion, delay);
  this.clearTimeout = (id) => {
    window.clearTimeout(id);
  };
}

/**
 * The scheduler code that will run in the Worker.
 * Workers have a built-in `self` variable similar to `window`.
 */
function WorkerScheduler() {
  const _tasks = {};
  self.onmessage = (e) => {
    const cmd = e.data;
    const id = _tasks[cmd.id];
    switch (cmd.type) {
      case "setTimeout":
        _tasks[cmd.id] = self.setTimeout(() => {
          delete _tasks[cmd.id];
          self.postMessage({
            id: cmd.id,
          });
        }, cmd.delay);
        break;
      case "clearTimeout":
        delete _tasks[cmd.id];
        if (id) {
          self.clearTimeout(id);
        }
        break;
      default:
        throw "Unknown command " + cmd.type;
    }
  };
}

/**
 * The constructor for a CometD object, identified by an optional name.
 * The default name is the string 'default'.
 * @param name the optional name of this cometd object
 */
export function CometD(name) {
  const _scheduler = new Scheduler();
  const _cometd = this;
  const _name = name || "default";
  let _crossDomain = false;
  const _transports = new TransportRegistry();
  let _transport;
  let _status = "disconnected";
  let _messageId = 0;
  let _clientId = null;
  let _batch = 0;
  let _messageQueue = [];
  let _internalBatch = false;
  let _listenerId = 0;
  let _listeners = {};
  const _transportListeners = {};
  let _backoff = 0;
  let _scheduledSend = null;
  const _extensions = [];
  let _advice = {};
  let _handshakeProps;
  let _handshakeCallback;
  const _callbacks = {};
  const _remoteCalls = {};
  let _reestablish = false;
  let _connected = false;
  let _unconnectTime = 0;
  let _handshakeMessages = 0;
  let _metaConnect = null;
  let _config = {
    useWorkerScheduler: true,
    protocol: null,
    stickyReconnect: true,
    connectTimeout: 0,
    maxConnections: 2,
    backoffIncrement: 1000,
    maxBackoff: 60000,
    logLevel: "info",
    maxNetworkDelay: 10000,
    requestHeaders: {},
    appendMessageTypeToURL: true,
    autoBatch: false,
    urls: {},
    maxURILength: 2000,
    maxSendBayeuxMessageSize: 8192,
    advice: {
      timeout: 60000,
      interval: 0,
      reconnect: undefined,
      maxInterval: 0,
    },
  };

  function _fieldValue(object, name) {
    try {
      return object[name];
    } catch (x) {
      return undefined;
    }
  }

  /**
   * Mixes in the given objects into the target object by copying the properties.
   * @param deep if the copy must be deep
   * @param target the target object
   * @param objects the objects whose properties are copied into the target
   */
  this._mixin = function (deep, target, objects) {
    const result = target || {};

    // Skip first 2 parameters (deep and target), and loop over the others
    for (let i = 2; i < arguments.length; ++i) {
      const object = arguments[i];

      if (object === undefined || object === null) {
        continue;
      }

      for (let propName in object) {
        if (object.hasOwnProperty(propName)) {
          const prop = _fieldValue(object, propName);
          const targ = _fieldValue(result, propName);

          // Avoid infinite loops
          if (prop === target) {
            continue;
          }
          // Do not mixin undefined values
          if (prop === undefined) {
            continue;
          }

          if (deep && typeof prop === "object" && prop !== null) {
            if (prop instanceof Array) {
              result[propName] = this._mixin(
                deep,
                targ instanceof Array ? targ : [],
                prop
              );
            } else {
              const source =
                typeof targ === "object" && !(targ instanceof Array)
                  ? targ
                  : {};
              result[propName] = this._mixin(deep, source, prop);
            }
          } else {
            result[propName] = prop;
          }
        }
      }
    }

    return result;
  };

  function _isString(value) {
    return Utils.isString(value);
  }

  function _isAlpha(char) {
    if (char >= "A" && char <= "Z") {
      return true;
    }
    return char >= "a" && char <= "z";
  }

  function _isNumeric(char) {
    return char >= "0" && char <= "9";
  }

  function _isAllowed(char) {
    switch (char) {
      case " ":
      case "!":
      case "#":
      case "$":
      case "(":
      case ")":
      case "*":
      case "+":
      case "-":
      case ".":
      case "/":
      case "@":
      case "_":
      case "{":
      case "~":
      case "}":
        return true;
      default:
        return false;
    }
  }

  function _isValidChannel(value) {
    if (!_isString(value)) {
      return false;
    }
    if (value.length < 2) {
      return false;
    }
    if (value.charAt(0) !== "/") {
      return false;
    }
    for (let i = 1; i < value.length; ++i) {
      const char = value.charAt(i);
      if (_isAlpha(char) || _isNumeric(char) || _isAllowed(char)) {
        continue;
      }
      return false;
    }
    return true;
  }

  function _isFunction(value) {
    if (value === undefined || value === null) {
      return false;
    }
    return typeof value === "function";
  }

  function _zeroPad(value, length) {
    let result = "";
    while (--length > 0) {
      if (value >= Math.pow(10, length)) {
        break;
      }
      result += "0";
    }
    result += value;
    return result;
  }

  function _log(level, args) {
    if (window.console) {
      const logger = window.console[level];
      if (_isFunction(logger)) {
        const now = new Date();
        [].splice.call(
          args,
          0,
          0,
          _zeroPad(now.getHours(), 2) +
            ":" +
            _zeroPad(now.getMinutes(), 2) +
            ":" +
            _zeroPad(now.getSeconds(), 2) +
            "." +
            _zeroPad(now.getMilliseconds(), 3)
        );
        logger.apply(window.console, args);
      }
    }
  }

  this._warn = function () {
    _log("warn", arguments);
  };

  this._info = function () {
    if (_config.logLevel !== "warn") {
      _log("info", arguments);
    }
  };

  this._debug = function () {
    if (_config.logLevel === "debug") {
      _log("debug", arguments);
    }
  };

  function _splitURL(url) {
    // [1] = protocol://,
    // [2] = host:port,
    // [3] = host,
    // [4] = IPv6_host,
    // [5] = IPv4_host,
    // [6] = :port,
    // [7] = port,
    // [8] = uri,
    // [9] = rest (query / fragment)
    return new RegExp(
      "(^https?://)?(((\\[[^\\]]+])|([^:/?#]+))(:(\\d+))?)?([^?#]*)(.*)?"
    ).exec(url);
  }

  /**
   * Returns whether the given hostAndPort is cross domain.
   * The default implementation checks against window.location.host
   * but this function can be overridden to make it work in non-browser
   * environments.
   *
   * @param hostAndPort the host and port in format host:port
   * @return whether the given hostAndPort is cross domain
   */
  this._isCrossDomain = (hostAndPort) => {
    if (window.location && window.location.host) {
      if (hostAndPort) {
        return hostAndPort !== window.location.host;
      }
    }
    return false;
  };

  function _configure(configuration) {
    _cometd._debug("Configuring cometd object with", configuration);
    // Support old style param, where only the Bayeux server URL was passed.
    if (_isString(configuration)) {
      configuration = {
        url: configuration,
      };
    }
    if (!configuration) {
      configuration = {};
    }

    _config = _cometd._mixin(false, _config, configuration);

    const url = _cometd.getURL();
    if (!url) {
      throw "Missing required configuration parameter 'url' specifying the Bayeux server URL";
    }

    // Check if we're cross domain.
    const urlParts = _splitURL(url);
    const hostAndPort = urlParts[2];
    const uri = urlParts[8];
    const afterURI = urlParts[9];
    _crossDomain = _cometd._isCrossDomain(hostAndPort);

    // Check if appending extra path is supported.
    if (_config.appendMessageTypeToURL) {
      if (afterURI !== undefined && afterURI.length > 0) {
        _cometd._info(
          "Appending message type to URI " +
            uri +
            afterURI +
            " is not supported, disabling 'appendMessageTypeToURL' configuration"
        );
        _config.appendMessageTypeToURL = false;
      } else {
        const uriSegments = uri.split("/");
        let lastSegmentIndex = uriSegments.length - 1;
        if (uri.match(/\/$/)) {
          lastSegmentIndex -= 1;
        }
        if (uriSegments[lastSegmentIndex].indexOf(".") >= 0) {
          // Very likely the CometD servlet's URL pattern is mapped to an extension, such as *.cometd
          // It will be difficult to add the extra path in this case
          _cometd._info(
            "Appending message type to URI " +
              uri +
              " is not supported, disabling 'appendMessageTypeToURL' configuration"
          );
          _config.appendMessageTypeToURL = false;
        }
      }
    }

    if (
      window.Worker &&
      window.Blob &&
      window.URL &&
      _config.useWorkerScheduler
    ) {
      let code = WorkerScheduler.toString();
      // Remove the function declaration, the opening brace and the closing brace.
      code = code.substring(code.indexOf("{") + 1, code.lastIndexOf("}"));
      const blob = new window.Blob([code], {
        type: "application/json",
      });
      const blobURL = window.URL.createObjectURL(blob);
      const worker = new window.Worker(blobURL);
      _scheduler.setTimeout = (funktion, delay) => {
        const id = _scheduler.register(funktion);
        worker.postMessage({
          id: id,
          type: "setTimeout",
          delay: delay,
        });
        return id;
      };
      _scheduler.clearTimeout = (id) => {
        _scheduler.unregister(id);
        worker.postMessage({
          id: id,
          type: "clearTimeout",
        });
      };
      worker.onmessage = (e) => {
        const id = e.data.id;
        const funktion = _scheduler.unregister(id);
        if (funktion) {
          funktion();
        }
      };
    }
  }

  function _removeListener(subscription) {
    if (subscription) {
      const subscriptions = _listeners[subscription.channel];
      if (subscriptions && subscriptions[subscription.id]) {
        delete subscriptions[subscription.id];
        _cometd._debug(
          "Removed",
          subscription.listener ? "listener" : "subscription",
          subscription
        );
      }
    }
  }

  function _removeSubscription(subscription) {
    if (subscription && !subscription.listener) {
      _removeListener(subscription);
    }
  }

  function _clearSubscriptions() {
    for (let channel in _listeners) {
      if (_listeners.hasOwnProperty(channel)) {
        const subscriptions = _listeners[channel];
        if (subscriptions) {
          for (let id in subscriptions) {
            if (subscriptions.hasOwnProperty(id)) {
              _removeSubscription(subscriptions[id]);
            }
          }
        }
      }
    }
  }

  function _setStatus(newStatus) {
    if (_status !== newStatus) {
      _cometd._debug("Status", _status, "->", newStatus);
      _status = newStatus;
    }
  }

  function _isDisconnected() {
    return _status === "disconnecting" || _status === "disconnected";
  }

  function _nextMessageId() {
    const result = ++_messageId;
    return "" + result;
  }

  function _applyExtension(scope, callback, name, message, outgoing) {
    try {
      return callback.call(scope, message);
    } catch (x) {
      const handler = _cometd.onExtensionException;
      if (_isFunction(handler)) {
        _cometd._debug("Invoking extension exception handler", name, x);
        try {
          handler.call(_cometd, x, name, outgoing, message);
        } catch (xx) {
          _cometd._info(
            "Exception during execution of extension exception handler",
            name,
            xx
          );
        }
      } else {
        _cometd._info("Exception during execution of extension", name, x);
      }
      return message;
    }
  }

  function _applyIncomingExtensions(message) {
    for (let i = 0; i < _extensions.length; ++i) {
      if (message === undefined || message === null) {
        break;
      }

      const extension = _extensions[i];
      const callback = extension.extension.incoming;
      if (_isFunction(callback)) {
        const result = _applyExtension(
          extension.extension,
          callback,
          extension.name,
          message,
          false
        );
        message = result === undefined ? message : result;
      }
    }
    return message;
  }

  function _applyOutgoingExtensions(message) {
    for (let i = _extensions.length - 1; i >= 0; --i) {
      if (message === undefined || message === null) {
        break;
      }

      const extension = _extensions[i];
      const callback = extension.extension.outgoing;
      if (_isFunction(callback)) {
        const result = _applyExtension(
          extension.extension,
          callback,
          extension.name,
          message,
          true
        );
        message = result === undefined ? message : result;
      }
    }
    return message;
  }

  function _notify(channel, message) {
    const subscriptions = _listeners[channel];
    if (subscriptions) {
      for (let id in subscriptions) {
        if (subscriptions.hasOwnProperty(id)) {
          const subscription = subscriptions[id];
          // Subscriptions may come and go, so the array may have 'holes'
          if (subscription) {
            try {
              subscription.callback.call(subscription.scope, message);
            } catch (x) {
              const handler = _cometd.onListenerException;
              if (_isFunction(handler)) {
                _cometd._debug(
                  "Invoking listener exception handler",
                  subscription,
                  x
                );
                try {
                  handler.call(
                    _cometd,
                    x,
                    subscription,
                    subscription.listener,
                    message
                  );
                } catch (xx) {
                  _cometd._info(
                    "Exception during execution of listener exception handler",
                    subscription,
                    xx
                  );
                }
              } else {
                _cometd._info(
                  "Exception during execution of listener",
                  subscription,
                  message,
                  x
                );
              }
            }
          }
        }
      }
    }
  }

  function _notifyListeners(channel, message) {
    // Notify direct listeners
    _notify(channel, message);

    // Notify the globbing listeners
    const channelParts = channel.split("/");
    const last = channelParts.length - 1;
    for (let i = last; i > 0; --i) {
      let channelPart = channelParts.slice(0, i).join("/") + "/*";
      // We don't want to notify /foo/* if the channel is /foo/bar/baz,
      // so we stop at the first non recursive globbing
      if (i === last) {
        _notify(channelPart, message);
      }
      // Add the recursive globber and notify
      channelPart += "*";
      _notify(channelPart, message);
    }
  }

  function _cancelDelayedSend() {
    if (_scheduledSend !== null) {
      _cometd.clearTimeout(_scheduledSend);
    }
    _scheduledSend = null;
  }

  function _delayedSend(operation, delay) {
    _cancelDelayedSend();
    const time = _advice.interval + delay;
    _cometd._debug(
      "Function scheduled in",
      time,
      "ms, interval =",
      _advice.interval,
      "backoff =",
      _backoff,
      operation
    );
    _scheduledSend = _cometd.setTimeout(operation, time);
  }

  // Needed to break cyclic dependencies between function definitions
  let _handleMessages;
  let _handleFailure;

  /**
   * Delivers the messages to the CometD server
   * @param messages the array of messages to send
   * @param metaConnect true if this send is on /meta/connect
   * @param extraPath an extra path to append to the Bayeux server URL
   */
  function _send(messages, metaConnect, extraPath) {
    // We must be sure that the messages have a clientId.
    // This is not guaranteed since the handshake may take time to return
    // (and hence the clientId is not known yet) and the application
    // may create other messages.
    for (let i = 0; i < messages.length; ++i) {
      let message = messages[i];
      const messageId = message.id;

      if (_clientId) {
        message.clientId = _clientId;
      }

      message = _applyOutgoingExtensions(message);
      if (message !== undefined && message !== null) {
        // Extensions may have modified the message id, but we need to own it.
        message.id = messageId;
        messages[i] = message;
      } else {
        delete _callbacks[messageId];
        messages.splice(i--, 1);
      }
    }

    if (messages.length === 0) {
      return;
    }

    if (metaConnect) {
      _metaConnect = messages[0];
    }

    let url = _cometd.getURL();
    if (_config.appendMessageTypeToURL) {
      // If url does not end with '/', then append it
      if (!url.match(/\/$/)) {
        url = url + "/";
      }
      if (extraPath) {
        url = url + extraPath;
      }
    }

    const envelope = {
      url: url,
      sync: false,
      messages: messages,
      onSuccess: (rcvdMessages) => {
        try {
          _handleMessages.call(_cometd, rcvdMessages);
        } catch (x) {
          _cometd._info("Exception during handling of messages", x);
        }
      },
      onFailure: (conduit, messages, failure) => {
        try {
          const transport = _cometd.getTransport();
          failure.connectionType = transport ? transport.getType() : "unknown";
          _handleFailure.call(_cometd, conduit, messages, failure);
        } catch (x) {
          _cometd._info("Exception during handling of failure", x);
        }
      },
    };
    _cometd._debug("Send", envelope);
    _transport.send(envelope, metaConnect);
  }

  function _queueSend(message) {
    if (_batch > 0 || _internalBatch === true) {
      _messageQueue.push(message);
    } else {
      _send([message], false);
    }
  }

  /**
   * Sends a complete bayeux message.
   * This method is exposed as a public so that extensions may use it
   * to send bayeux message directly, for example in case of re-sending
   * messages that have already been sent but that for some reason must
   * be resent.
   */
  this.send = _queueSend;

  function _resetBackoff() {
    _backoff = 0;
  }

  function _increaseBackoff() {
    if (_backoff < _config.maxBackoff) {
      _backoff += _config.backoffIncrement;
    }
    return _backoff;
  }

  /**
   * Starts a the batch of messages to be sent in a single request.
   * @see #_endBatch(sendMessages)
   */
  function _startBatch() {
    ++_batch;
    _cometd._debug("Starting batch, depth", _batch);
  }

  function _flushBatch() {
    const messages = _messageQueue;
    _messageQueue = [];
    if (messages.length > 0) {
      _send(messages, false);
    }
  }

  /**
   * Ends the batch of messages to be sent in a single request,
   * optionally sending messages present in the message queue depending
   * on the given argument.
   * @see _startBatch()
   */
  function _endBatch() {
    --_batch;
    _cometd._debug("Ending batch, depth", _batch);
    if (_batch < 0) {
      throw "Calls to startBatch() and endBatch() are not paired";
    }

    if (_batch === 0 && !_isDisconnected() && !_internalBatch) {
      _flushBatch();
    }
  }

  /**
   * Sends the connect message
   */
  function _connect() {
    if (!_isDisconnected()) {
      const bayeuxMessage = {
        id: _nextMessageId(),
        channel: "/meta/connect",
        connectionType: _transport.getType(),
      };

      // In case of reload or temporary loss of connection
      // we want the next successful connect to return immediately
      // instead of being held by the server, so that connect listeners
      // can be notified that the connection has been re-established
      if (!_connected) {
        bayeuxMessage.advice = {
          timeout: 0,
        };
      }

      _setStatus("connecting");
      _cometd._debug("Connect sent", bayeuxMessage);
      _send([bayeuxMessage], true, "connect");
      _setStatus("connected");
    }
  }

  function _delayedConnect(delay) {
    _setStatus("connecting");
    _delayedSend(() => {
      _connect();
    }, delay);
  }

  function _updateAdvice(newAdvice) {
    if (newAdvice) {
      _advice = _cometd._mixin(false, {}, _config.advice, newAdvice);
      _cometd._debug("New advice", _advice);
    }
  }

  function _disconnect(abort) {
    _cancelDelayedSend();
    if (abort && _transport) {
      _transport.abort();
    }
    _crossDomain = false;
    _transport = null;
    _setStatus("disconnected");
    _clientId = null;
    _batch = 0;
    _resetBackoff();
    _reestablish = false;
    _connected = false;
    _unconnectTime = 0;
    _metaConnect = null;

    // Fail any existing queued message
    if (_messageQueue.length > 0) {
      const messages = _messageQueue;
      _messageQueue = [];
      _handleFailure.call(_cometd, undefined, messages, {
        reason: "Disconnected",
      });
    }
  }

  function _notifyTransportException(oldTransport, newTransport, failure) {
    const handler = _cometd.onTransportException;
    if (_isFunction(handler)) {
      _cometd._debug(
        "Invoking transport exception handler",
        oldTransport,
        newTransport,
        failure
      );
      try {
        handler.call(_cometd, failure, oldTransport, newTransport);
      } catch (x) {
        _cometd._info(
          "Exception during execution of transport exception handler",
          x
        );
      }
    }
  }

  /**
   * Sends the initial handshake message
   */
  function _handshake(handshakeProps, handshakeCallback) {
    if (_isFunction(handshakeProps)) {
      handshakeCallback = handshakeProps;
      handshakeProps = undefined;
    }

    _clientId = null;

    _clearSubscriptions();

    // Reset the transports if we're not retrying the handshake
    if (_isDisconnected()) {
      _transports.reset(true);
    }

    // Reset the advice.
    _updateAdvice({});

    _batch = 0;

    // Mark the start of an internal batch.
    // This is needed because handshake and connect are async.
    // It may happen that the application calls init() then subscribe()
    // and the subscribe message is sent before the connect message, if
    // the subscribe message is not held until the connect message is sent.
    // So here we start a batch to hold temporarily any message until
    // the connection is fully established.
    _internalBatch = true;

    // Save the properties provided by the user, so that
    // we can reuse them during automatic re-handshake
    _handshakeProps = handshakeProps;
    _handshakeCallback = handshakeCallback;

    const version = "1.0";

    // Figure out the transports to send to the server
    const url = _cometd.getURL();
    const transportTypes = _transports.findTransportTypes(
      version,
      _crossDomain,
      url
    );

    const bayeuxMessage = {
      id: _nextMessageId(),
      version: version,
      minimumVersion: version,
      channel: "/meta/handshake",
      supportedConnectionTypes: transportTypes,
      advice: {
        timeout: _advice.timeout,
        interval: _advice.interval,
      },
    };
    // Do not allow the user to override important fields.
    const message = _cometd._mixin(false, {}, _handshakeProps, bayeuxMessage);

    // Save the callback.
    _cometd._putCallback(message.id, handshakeCallback);

    // Pick up the first available transport as initial transport
    // since we don't know if the server supports it
    if (!_transport) {
      _transport = _transports.negotiateTransport(
        transportTypes,
        version,
        _crossDomain,
        url
      );
      if (!_transport) {
        const failure =
          "Could not find initial transport among: " +
          _transports.getTransportTypes();
        _cometd._warn(failure);
        throw failure;
      }
    }

    _cometd._debug("Initial transport is", _transport.getType());

    // We started a batch to hold the application messages,
    // so here we must bypass it and send immediately.
    _setStatus("handshaking");
    _cometd._debug("Handshake sent", message);
    _send([message], false, "handshake");
  }

  function _delayedHandshake(delay) {
    _setStatus("handshaking");

    // We will call _handshake() which will reset _clientId, but we want to avoid
    // that between the end of this method and the call to _handshake() someone may
    // call publish() (or other methods that call _queueSend()).
    _internalBatch = true;

    _delayedSend(() => {
      _handshake(_handshakeProps, _handshakeCallback);
    }, delay);
  }

  function _notifyCallback(callback, message) {
    try {
      callback.call(_cometd, message);
    } catch (x) {
      const handler = _cometd.onCallbackException;
      if (_isFunction(handler)) {
        _cometd._debug("Invoking callback exception handler", x);
        try {
          handler.call(_cometd, x, message);
        } catch (xx) {
          _cometd._info(
            "Exception during execution of callback exception handler",
            xx
          );
        }
      } else {
        _cometd._info("Exception during execution of message callback", x);
      }
    }
  }

  this._getCallback = (messageId) => _callbacks[messageId];

  this._putCallback = function (messageId, callback) {
    const result = this._getCallback(messageId);
    if (_isFunction(callback)) {
      _callbacks[messageId] = callback;
    }
    return result;
  };

  function _handleCallback(message) {
    const callback = _cometd._getCallback([message.id]);
    if (_isFunction(callback)) {
      delete _callbacks[message.id];
      _notifyCallback(callback, message);
    }
  }

  function _handleRemoteCall(message) {
    const context = _remoteCalls[message.id];
    delete _remoteCalls[message.id];
    if (context) {
      _cometd._debug(
        "Handling remote call response for",
        message,
        "with context",
        context
      );

      // Clear the timeout, if present.
      const timeout = context.timeout;
      if (timeout) {
        _cometd.clearTimeout(timeout);
      }

      const callback = context.callback;
      if (_isFunction(callback)) {
        _notifyCallback(callback, message);
        return true;
      }
    }
    return false;
  }

  this.onTransportFailure = function (message, failureInfo, failureHandler) {
    this._debug("Transport failure", failureInfo, "for", message);

    const transports = this.getTransportRegistry();
    const url = this.getURL();
    const crossDomain = this._isCrossDomain(_splitURL(url)[2]);
    const version = "1.0";
    const transportTypes = transports.findTransportTypes(
      version,
      crossDomain,
      url
    );

    if (failureInfo.action === "none") {
      if (message.channel === "/meta/handshake") {
        if (!failureInfo.transport) {
          const failure =
            "Could not negotiate transport, client=[" +
            transportTypes +
            "], server=[" +
            message.supportedConnectionTypes +
            "]";
          this._warn(failure);
          _notifyTransportException(_transport.getType(), null, {
            reason: failure,
            connectionType: _transport.getType(),
            transport: _transport,
          });
        }
      }
    } else {
      failureInfo.delay = this.getBackoffPeriod();
      // Different logic depending on whether we are handshaking or connecting.
      if (message.channel === "/meta/handshake") {
        if (!failureInfo.transport) {
          // The transport is invalid, try to negotiate again.
          const oldTransportType = _transport ? _transport.getType() : null;
          const newTransport = transports.negotiateTransport(
            transportTypes,
            version,
            crossDomain,
            url
          );
          if (!newTransport) {
            this._warn(
              "Could not negotiate transport, client=[" + transportTypes + "]"
            );
            _notifyTransportException(oldTransportType, null, message.failure);
            failureInfo.action = "none";
          } else {
            const newTransportType = newTransport.getType();
            this._debug("Transport", oldTransportType, "->", newTransportType);
            _notifyTransportException(
              oldTransportType,
              newTransportType,
              message.failure
            );
            failureInfo.action = "handshake";
            failureInfo.transport = newTransport;
          }
        }

        if (failureInfo.action !== "none") {
          this.increaseBackoffPeriod();
        }
      } else {
        const now = new Date().getTime();

        if (_unconnectTime === 0) {
          _unconnectTime = now;
        }

        if (failureInfo.action === "retry") {
          failureInfo.delay = this.increaseBackoffPeriod();
          // Check whether we may switch to handshaking.
          const maxInterval = _advice.maxInterval;
          if (maxInterval > 0) {
            const expiration = _advice.timeout + _advice.interval + maxInterval;
            const unconnected = now - _unconnectTime;
            if (unconnected + _backoff > expiration) {
              failureInfo.action = "handshake";
            }
          }
        }

        if (failureInfo.action === "handshake") {
          failureInfo.delay = 0;
          transports.reset(false);
          this.resetBackoffPeriod();
        }
      }
    }

    failureHandler.call(_cometd, failureInfo);
  };

  function _handleTransportFailure(failureInfo) {
    _cometd._debug("Transport failure handling", failureInfo);

    if (failureInfo.transport) {
      _transport = failureInfo.transport;
    }

    if (failureInfo.url) {
      _transport.setURL(failureInfo.url);
    }

    const action = failureInfo.action;
    const delay = failureInfo.delay || 0;
    switch (action) {
      case "handshake":
        _delayedHandshake(delay);
        break;
      case "retry":
        _delayedConnect(delay);
        break;
      case "none":
        _disconnect(true);
        break;
      default:
        throw "Unknown action " + action;
    }
  }

  function _failHandshake(message, failureInfo) {
    _handleCallback(message);
    _notifyListeners("/meta/handshake", message);
    _notifyListeners("/meta/unsuccessful", message);

    // The listeners may have disconnected.
    if (_isDisconnected()) {
      failureInfo.action = "none";
    }

    _cometd.onTransportFailure.call(
      _cometd,
      message,
      failureInfo,
      _handleTransportFailure
    );
  }

  function _handshakeResponse(message) {
    const url = _cometd.getURL();
    if (message.successful) {
      const crossDomain = _cometd._isCrossDomain(_splitURL(url)[2]);
      const newTransport = _transports.negotiateTransport(
        message.supportedConnectionTypes,
        message.version,
        crossDomain,
        url
      );
      if (newTransport === null) {
        message.successful = false;
        _failHandshake(message, {
          cause: "negotiation",
          action: "none",
          transport: null,
        });
        return;
      } else if (_transport !== newTransport) {
        _cometd._debug(
          "Transport",
          _transport.getType(),
          "->",
          newTransport.getType()
        );
        _transport = newTransport;
      }

      _clientId = message.clientId;

      // End the internal batch and allow held messages from the application
      // to go to the server (see _handshake() where we start the internal batch).
      _internalBatch = false;
      _flushBatch();

      // Here the new transport is in place, as well as the clientId, so
      // the listeners can perform a publish() if they want.
      // Notify the listeners before the connect below.
      message.reestablish = _reestablish;
      _reestablish = true;

      _handleCallback(message);
      _notifyListeners("/meta/handshake", message);

      _handshakeMessages = message["x-messages"] || 0;

      const action = _isDisconnected() ? "none" : _advice.reconnect || "retry";
      switch (action) {
        case "retry":
          _resetBackoff();
          if (_handshakeMessages === 0) {
            _delayedConnect(0);
          } else {
            _cometd._debug(
              "Processing",
              _handshakeMessages,
              "handshake-delivered messages"
            );
          }
          break;
        case "none":
          _disconnect(true);
          break;
        default:
          throw "Unrecognized advice action " + action;
      }
    } else {
      _failHandshake(message, {
        cause: "unsuccessful",
        action: _advice.reconnect || "handshake",
        transport: _transport,
      });
    }
  }

  function _handshakeFailure(message) {
    _failHandshake(message, {
      cause: "failure",
      action: "handshake",
      transport: null,
    });
  }

  function _matchMetaConnect(connect) {
    if (_status === "disconnected") {
      return true;
    }
    if (_metaConnect && _metaConnect.id === connect.id) {
      _metaConnect = null;
      return true;
    }
    return false;
  }

  function _failConnect(message, failureInfo) {
    // Notify the listeners after the status change but before the next action.
    _notifyListeners("/meta/connect", message);
    _notifyListeners("/meta/unsuccessful", message);

    // The listeners may have disconnected.
    if (_isDisconnected()) {
      failureInfo.action = "none";
    }

    _cometd.onTransportFailure.call(
      _cometd,
      message,
      failureInfo,
      _handleTransportFailure
    );
  }

  function _connectResponse(message) {
    if (_matchMetaConnect(message)) {
      _connected = message.successful;
      if (_connected) {
        _notifyListeners("/meta/connect", message);

        // Normally, the advice will say "reconnect: 'retry', interval: 0"
        // and the server will hold the request, so when a response returns
        // we immediately call the server again (long polling).
        // Listeners can call disconnect(), so check the state after they run.
        const action = _isDisconnected()
          ? "none"
          : _advice.reconnect || "retry";
        switch (action) {
          case "retry":
            _resetBackoff();
            _delayedConnect(_backoff);
            break;
          case "none":
            _disconnect(false);
            break;
          default:
            throw "Unrecognized advice action " + action;
        }
      } else {
        _failConnect(message, {
          cause: "unsuccessful",
          action: _advice.reconnect || "retry",
          transport: _transport,
        });
      }
    } else {
      _cometd._debug("Mismatched /meta/connect reply", message);
    }
  }

  function _connectFailure(message) {
    if (_matchMetaConnect(message)) {
      _connected = false;
      _failConnect(message, {
        cause: "failure",
        action: "retry",
        transport: null,
      });
    } else {
      _cometd._debug("Mismatched /meta/connect failure", message);
    }
  }

  function _failDisconnect(message) {
    _disconnect(true);
    _handleCallback(message);
    _notifyListeners("/meta/disconnect", message);
    _notifyListeners("/meta/unsuccessful", message);
  }

  function _disconnectResponse(message) {
    if (message.successful) {
      // Wait for the /meta/connect to arrive.
      _disconnect(false);
      _handleCallback(message);
      _notifyListeners("/meta/disconnect", message);
    } else {
      _failDisconnect(message);
    }
  }

  function _disconnectFailure(message) {
    _failDisconnect(message);
  }

  function _failSubscribe(message) {
    const subscriptions = _listeners[message.subscription];
    if (subscriptions) {
      for (let id in subscriptions) {
        if (subscriptions.hasOwnProperty(id)) {
          const subscription = subscriptions[id];
          if (subscription && !subscription.listener) {
            delete subscriptions[id];
            _cometd._debug("Removed failed subscription", subscription);
          }
        }
      }
    }
    _handleCallback(message);
    _notifyListeners("/meta/subscribe", message);
    _notifyListeners("/meta/unsuccessful", message);
  }

  function _subscribeResponse(message) {
    if (message.successful) {
      _handleCallback(message);
      _notifyListeners("/meta/subscribe", message);
    } else {
      _failSubscribe(message);
    }
  }

  function _subscribeFailure(message) {
    _failSubscribe(message);
  }

  function _failUnsubscribe(message) {
    _handleCallback(message);
    _notifyListeners("/meta/unsubscribe", message);
    _notifyListeners("/meta/unsuccessful", message);
  }

  function _unsubscribeResponse(message) {
    if (message.successful) {
      _handleCallback(message);
      _notifyListeners("/meta/unsubscribe", message);
    } else {
      _failUnsubscribe(message);
    }
  }

  function _unsubscribeFailure(message) {
    _failUnsubscribe(message);
  }

  function _failMessage(message) {
    if (!_handleRemoteCall(message)) {
      _handleCallback(message);
      _notifyListeners("/meta/publish", message);
      _notifyListeners("/meta/unsuccessful", message);
    }
  }

  function _messageResponse(message) {
    if (message.data !== undefined) {
      if (!_handleRemoteCall(message)) {
        _notifyListeners(message.channel, message);
        if (_handshakeMessages > 0) {
          --_handshakeMessages;
          if (_handshakeMessages === 0) {
            _cometd._debug("Processed last handshake-delivered message");
            _delayedConnect(0);
          }
        }
      }
    } else {
      if (message.successful === undefined) {
        _cometd._warn("Unknown Bayeux Message", message);
      } else {
        if (message.successful) {
          _handleCallback(message);
          _notifyListeners("/meta/publish", message);
        } else {
          _failMessage(message);
        }
      }
    }
  }

  function _messageFailure(failure) {
    _failMessage(failure);
  }

  function _receive(message) {
    _unconnectTime = 0;

    message = _applyIncomingExtensions(message);
    if (message === undefined || message === null) {
      return;
    }

    _updateAdvice(message.advice);

    const channel = message.channel;
    switch (channel) {
      case "/meta/handshake":
        _handshakeResponse(message);
        break;
      case "/meta/connect":
        _connectResponse(message);
        break;
      case "/meta/disconnect":
        _disconnectResponse(message);
        break;
      case "/meta/subscribe":
        _subscribeResponse(message);
        break;
      case "/meta/unsubscribe":
        _unsubscribeResponse(message);
        break;
      default:
        _messageResponse(message);
        break;
    }
  }

  /**
   * Receives a message.
   * This method is exposed as a public so that extensions may inject
   * messages simulating that they had been received.
   */
  this.receive = _receive;

  _handleMessages = (rcvdMessages) => {
    _cometd._debug("Received", rcvdMessages);

    for (let i = 0; i < rcvdMessages.length; ++i) {
      const message = rcvdMessages[i];
      _receive(message);
    }
  };

  _handleFailure = (conduit, messages, failure) => {
    _cometd._debug("handleFailure", conduit, messages, failure);

    failure.transport = conduit;
    for (let i = 0; i < messages.length; ++i) {
      const message = messages[i];
      const failureMessage = {
        id: message.id,
        successful: false,
        channel: message.channel,
        failure: failure,
      };
      failure.message = message;
      switch (message.channel) {
        case "/meta/handshake":
          _handshakeFailure(failureMessage);
          break;
        case "/meta/connect":
          _connectFailure(failureMessage);
          break;
        case "/meta/disconnect":
          _disconnectFailure(failureMessage);
          break;
        case "/meta/subscribe":
          failureMessage.subscription = message.subscription;
          _subscribeFailure(failureMessage);
          break;
        case "/meta/unsubscribe":
          failureMessage.subscription = message.subscription;
          _unsubscribeFailure(failureMessage);
          break;
        default:
          _messageFailure(failureMessage);
          break;
      }
    }
  };

  function _hasSubscriptions(channel) {
    const subscriptions = _listeners[channel];
    if (subscriptions) {
      for (let id in subscriptions) {
        if (subscriptions.hasOwnProperty(id)) {
          if (subscriptions[id]) {
            return true;
          }
        }
      }
    }
    return false;
  }

  function _resolveScopedCallback(scope, callback) {
    const delegate = {
      scope: scope,
      method: callback,
    };
    if (_isFunction(scope)) {
      delegate.scope = undefined;
      delegate.method = scope;
    } else {
      if (_isString(callback)) {
        if (!scope) {
          throw "Invalid scope " + scope;
        }
        delegate.method = scope[callback];
        if (!_isFunction(delegate.method)) {
          throw "Invalid callback " + callback + " for scope " + scope;
        }
      } else if (!_isFunction(callback)) {
        throw "Invalid callback " + callback;
      }
    }
    return delegate;
  }

  function _addListener(channel, scope, callback, isListener) {
    // The data structure is a map<channel, subscription[]>, where each subscription
    // holds the callback to be called and its scope.

    const delegate = _resolveScopedCallback(scope, callback);
    _cometd._debug(
      "Adding",
      isListener ? "listener" : "subscription",
      "on",
      channel,
      "with scope",
      delegate.scope,
      "and callback",
      delegate.method
    );

    const id = ++_listenerId;
    const subscription = {
      id: id,
      channel: channel,
      scope: delegate.scope,
      callback: delegate.method,
      listener: isListener,
    };

    let subscriptions = _listeners[channel];
    if (!subscriptions) {
      subscriptions = {};
      _listeners[channel] = subscriptions;
    }

    subscriptions[id] = subscription;

    _cometd._debug(
      "Added",
      isListener ? "listener" : "subscription",
      subscription
    );

    return subscription;
  }

  //
  // PUBLIC API
  //

  /**
   * Registers the given transport under the given transport type.
   * The optional index parameter specifies the "priority" at which the
   * transport is registered (where 0 is the max priority).
   * If a transport with the same type is already registered, this function
   * does nothing and returns false.
   * @param type the transport type
   * @param transport the transport object
   * @param index the index at which this transport is to be registered
   * @return true if the transport has been registered, false otherwise
   * @see #unregisterTransport(type)
   */
  this.registerTransport = function (type, transport, index) {
    const result = _transports.add(type, transport, index);
    if (result) {
      this._debug("Registered transport", type);

      if (_isFunction(transport.registered)) {
        transport.registered(type, this);
      }
    }
    return result;
  };

  /**
   * Unregisters the transport with the given transport type.
   * @param type the transport type to unregister
   * @return the transport that has been unregistered,
   * or null if no transport was previously registered under the given transport type
   */
  this.unregisterTransport = function (type) {
    const transport = _transports.remove(type);
    if (transport !== null) {
      this._debug("Unregistered transport", type);

      if (_isFunction(transport.unregistered)) {
        transport.unregistered();
      }
    }
    return transport;
  };

  this.unregisterTransports = () => {
    _transports.clear();
  };

  /**
   * @return an array of all registered transport types
   */
  this.getTransportTypes = () => _transports.getTransportTypes();

  this.findTransport = (name) => _transports.find(name);

  /**
   * @returns the TransportRegistry object
   */
  this.getTransportRegistry = () => _transports;

  /**
   * Configures the initial Bayeux communication with the Bayeux server.
   * Configuration is passed via an object that must contain a mandatory field <code>url</code>
   * of type string containing the URL of the Bayeux server.
   * @param configuration the configuration object
   */
  this.configure = function (configuration) {
    _configure.call(this, configuration);
  };

  /**
   * Configures and establishes the Bayeux communication with the Bayeux server
   * via a handshake and a subsequent connect.
   * @param configuration the configuration object
   * @param handshakeProps an object to be merged with the handshake message
   * @see #configure(configuration)
   * @see #handshake(handshakeProps)
   */
  this.init = function (configuration, handshakeProps) {
    this.configure(configuration);
    this.handshake(handshakeProps);
  };

  /**
   * Establishes the Bayeux communication with the Bayeux server
   * via a handshake and a subsequent connect.
   * @param handshakeProps an object to be merged with the handshake message
   * @param handshakeCallback a function to be invoked when the handshake is acknowledged
   */
  this.handshake = (handshakeProps, handshakeCallback) => {
    if (_status !== "disconnected") {
      throw "Illegal state: handshaken";
    }
    _handshake(handshakeProps, handshakeCallback);
  };

  /**
   * Disconnects from the Bayeux server.
   * @param disconnectProps an object to be merged with the disconnect message
   * @param disconnectCallback a function to be invoked when the disconnect is acknowledged
   */
  this.disconnect = function (disconnectProps, disconnectCallback) {
    if (_isDisconnected()) {
      return;
    }

    if (_isFunction(disconnectProps)) {
      disconnectCallback = disconnectProps;
      disconnectProps = undefined;
    }

    const bayeuxMessage = {
      id: _nextMessageId(),
      channel: "/meta/disconnect",
    };
    // Do not allow the user to override important fields.
    const message = this._mixin(false, {}, disconnectProps, bayeuxMessage);

    // Save the callback.
    _cometd._putCallback(message.id, disconnectCallback);

    _setStatus("disconnecting");
    _send([message], false, "disconnect");
  };

  /**
   * Marks the start of a batch of application messages to be sent to the server
   * in a single request, obtaining a single response containing (possibly) many
   * application reply messages.
   * Messages are held in a queue and not sent until {@link #endBatch()} is called.
   * If startBatch() is called multiple times, then an equal number of endBatch()
   * calls must be made to close and send the batch of messages.
   * @see #endBatch()
   */
  this.startBatch = () => {
    _startBatch();
  };

  /**
   * Marks the end of a batch of application messages to be sent to the server
   * in a single request.
   * @see #startBatch()
   */
  this.endBatch = () => {
    _endBatch();
  };

  /**
   * Executes the given callback in the given scope, surrounded by a {@link #startBatch()}
   * and {@link #endBatch()} calls.
   * @param scope the scope of the callback, may be omitted
   * @param callback the callback to be executed within {@link #startBatch()} and {@link #endBatch()} calls
   */
  this.batch = function (scope, callback) {
    const delegate = _resolveScopedCallback(scope, callback);
    this.startBatch();
    try {
      delegate.method.call(delegate.scope);
      this.endBatch();
    } catch (x) {
      this._info("Exception during execution of batch", x);
      this.endBatch();
      throw x;
    }
  };

  /**
   * Adds a transport listener for the specified transport event,
   * executing the given callback when the event happens.
   *
   * The currently supported event is `timeout`.
   *
   * The callback function takes an array of messages for which
   * the event happened.
   *
   * For the 'timeout' event, the callback function may return a
   * positive value that extends the wait for message replies by
   * the returned amount, in milliseconds.
   *
   * @param {String} event the type of transport event
   * @param {Function} callback the function associate to the given transport event
   * @see #removeTransportListener
   */
  this.addTransportListener = (event, callback) => {
    if (event !== "timeout") {
      throw "Unsupported event " + event;
    }
    let callbacks = _transportListeners[event];
    if (!callbacks) {
      _transportListeners[event] = callbacks = [];
    }
    callbacks.push(callback);
  };

  /**
   * Removes the transport listener for the specified transport event.
   * @param {String} event the type of transport event
   * @param {Function} callback the function disassociate from the given transport event
   * @return {boolean} whether the disassociation was successful
   * @see #addTransportListener
   */
  this.removeTransportListener = (event, callback) => {
    const callbacks = _transportListeners[event];
    if (callbacks) {
      const index = callbacks.indexOf(callback);
      if (index >= 0) {
        callbacks.splice(index, 1);
        return true;
      }
    }
    return false;
  };

  this._getTransportListeners = (event) => _transportListeners[event];

  /**
   * Adds a listener for bayeux messages, performing the given callback in the given scope
   * when a message for the given channel arrives.
   * @param channel the channel the listener is interested to
   * @param scope the scope of the callback, may be omitted
   * @param callback the callback to call when a message is sent to the channel
   * @returns the subscription handle to be passed to {@link #removeListener(object)}
   * @see #removeListener(subscription)
   */
  this.addListener = function (channel, scope, callback) {
    if (arguments.length < 2) {
      throw "Illegal arguments number: required 2, got " + arguments.length;
    }
    if (!_isString(channel)) {
      throw "Illegal argument type: channel must be a string";
    }

    return _addListener(channel, scope, callback, true);
  };

  /**
   * Removes the subscription obtained with a call to {@link #addListener(string, object, function)}.
   * @param subscription the subscription to unsubscribe.
   * @see #addListener(channel, scope, callback)
   */
  this.removeListener = (subscription) => {
    // Beware of subscription.id == 0, which is falsy => cannot use !subscription.id
    if (!subscription || !subscription.channel || !("id" in subscription)) {
      throw "Invalid argument: expected subscription, not " + subscription;
    }

    _removeListener(subscription);
  };

  /**
   * Removes all listeners registered with {@link #addListener(channel, scope, callback)} or
   * {@link #subscribe(channel, scope, callback)}.
   */
  this.clearListeners = () => {
    _listeners = {};
  };

  /**
   * Subscribes to the given channel, performing the given callback in the given scope
   * when a message for the channel arrives.
   * @param channel the channel to subscribe to
   * @param scope the scope of the callback, may be omitted
   * @param callback the callback to call when a message is sent to the channel
   * @param subscribeProps an object to be merged with the subscribe message
   * @param subscribeCallback a function to be invoked when the subscription is acknowledged
   * @return the subscription handle to be passed to {@link #unsubscribe(object)}
   */
  this.subscribe = function (
    channel,
    scope,
    callback,
    subscribeProps,
    subscribeCallback
  ) {
    if (arguments.length < 2) {
      throw "Illegal arguments number: required 2, got " + arguments.length;
    }
    if (!_isValidChannel(channel)) {
      throw "Illegal argument: invalid channel " + channel;
    }
    if (_isDisconnected()) {
      throw "Illegal state: disconnected";
    }

    // Normalize arguments
    if (_isFunction(scope)) {
      subscribeCallback = subscribeProps;
      subscribeProps = callback;
      callback = scope;
      scope = undefined;
    }
    if (_isFunction(subscribeProps)) {
      subscribeCallback = subscribeProps;
      subscribeProps = undefined;
    }

    // Only send the message to the server if this client has not yet subscribed to the channel
    const send = !_hasSubscriptions(channel);

    const subscription = _addListener(channel, scope, callback, false);

    if (send) {
      // Send the subscription message after the subscription registration to avoid
      // races where the server would send a message to the subscribers, but here
      // on the client the subscription has not been added yet to the data structures
      const bayeuxMessage = {
        id: _nextMessageId(),
        channel: "/meta/subscribe",
        subscription: channel,
      };
      // Do not allow the user to override important fields.
      const message = this._mixin(false, {}, subscribeProps, bayeuxMessage);

      // Save the callback.
      _cometd._putCallback(message.id, subscribeCallback);

      _queueSend(message);
    } else {
      if (_isFunction(subscribeCallback)) {
        // Keep the semantic of calling callbacks asynchronously.
        _cometd.setTimeout(() => {
          _notifyCallback(subscribeCallback, {
            id: _nextMessageId(),
            successful: true,
            channel: "/meta/subscribe",
            subscription: channel,
          });
        }, 0);
      }
    }

    return subscription;
  };

  /**
   * Unsubscribes the subscription obtained with a call to {@link #subscribe(string, object, function)}.
   * @param subscription the subscription to unsubscribe.
   * @param unsubscribeProps an object to be merged with the unsubscribe message
   * @param unsubscribeCallback a function to be invoked when the unsubscription is acknowledged
   */
  this.unsubscribe = function (
    subscription,
    unsubscribeProps,
    unsubscribeCallback
  ) {
    if (arguments.length < 1) {
      throw "Illegal arguments number: required 1, got " + arguments.length;
    }
    if (_isDisconnected()) {
      throw "Illegal state: disconnected";
    }

    if (_isFunction(unsubscribeProps)) {
      unsubscribeCallback = unsubscribeProps;
      unsubscribeProps = undefined;
    }

    // Remove the local listener before sending the message
    // This ensures that if the server fails, this client does not get notifications
    this.removeListener(subscription);

    const channel = subscription.channel;
    // Only send the message to the server if this client unsubscribes the last subscription
    if (!_hasSubscriptions(channel)) {
      const bayeuxMessage = {
        id: _nextMessageId(),
        channel: "/meta/unsubscribe",
        subscription: channel,
      };
      // Do not allow the user to override important fields.
      const message = this._mixin(false, {}, unsubscribeProps, bayeuxMessage);

      // Save the callback.
      _cometd._putCallback(message.id, unsubscribeCallback);

      _queueSend(message);
    } else {
      if (_isFunction(unsubscribeCallback)) {
        // Keep the semantic of calling callbacks asynchronously.
        _cometd.setTimeout(() => {
          _notifyCallback(unsubscribeCallback, {
            id: _nextMessageId(),
            successful: true,
            channel: "/meta/unsubscribe",
            subscription: channel,
          });
        }, 0);
      }
    }
  };

  this.resubscribe = function (subscription, subscribeProps) {
    _removeSubscription(subscription);
    if (subscription) {
      return this.subscribe(
        subscription.channel,
        subscription.scope,
        subscription.callback,
        subscribeProps
      );
    }
    return undefined;
  };

  /**
   * Removes all subscriptions added via {@link #subscribe(channel, scope, callback, subscribeProps)},
   * but does not remove the listeners added via {@link addListener(channel, scope, callback)}.
   */
  this.clearSubscriptions = () => {
    _clearSubscriptions();
  };

  /**
   * Publishes a message on the given channel, containing the given content.
   * @param channel the channel to publish the message to
   * @param content the content of the message
   * @param publishProps an object to be merged with the publish message
   * @param publishCallback a function to be invoked when the publish is acknowledged by the server
   */
  this.publish = function (channel, content, publishProps, publishCallback) {
    if (arguments.length < 1) {
      throw "Illegal arguments number: required 1, got " + arguments.length;
    }
    if (!_isValidChannel(channel)) {
      throw "Illegal argument: invalid channel " + channel;
    }
    if (/^\/meta\//.test(channel)) {
      throw "Illegal argument: cannot publish to meta channels";
    }
    if (_isDisconnected()) {
      throw "Illegal state: disconnected";
    }

    if (_isFunction(content)) {
      publishCallback = content;
      content = {};
      publishProps = undefined;
    } else if (_isFunction(publishProps)) {
      publishCallback = publishProps;
      publishProps = undefined;
    }

    const bayeuxMessage = {
      id: _nextMessageId(),
      channel: channel,
      data: content,
    };
    // Do not allow the user to override important fields.
    const message = this._mixin(false, {}, publishProps, bayeuxMessage);

    // Save the callback.
    _cometd._putCallback(message.id, publishCallback);

    _queueSend(message);
  };

  /**
   * Publishes a message with binary data on the given channel.
   * The binary data chunk may be an ArrayBuffer, a DataView, a TypedArray
   * (such as Uint8Array) or a plain integer array.
   * The meta data object may contain additional application data such as
   * a file name, a mime type, etc.
   * @param channel the channel to publish the message to
   * @param data the binary data to publish
   * @param last whether the binary data chunk is the last
   * @param meta an object containing meta data associated to the binary chunk
   * @param publishProps an object to be merged with the publish message
   * @param publishCallback a function to be invoked when the publish is acknowledged by the server
   */
  this.publishBinary = function (
    channel,
    data,
    last,
    meta,
    publishProps,
    publishCallback
  ) {
    if (_isFunction(data)) {
      publishCallback = data;
      data = new ArrayBuffer(0);
      last = true;
      meta = undefined;
      publishProps = undefined;
    } else if (_isFunction(last)) {
      publishCallback = last;
      last = true;
      meta = undefined;
      publishProps = undefined;
    } else if (_isFunction(meta)) {
      publishCallback = meta;
      meta = undefined;
      publishProps = undefined;
    } else if (_isFunction(publishProps)) {
      publishCallback = publishProps;
      publishProps = undefined;
    }
    const content = {
      meta: meta,
      data: data,
      last: last,
    };
    const ext = this._mixin(false, publishProps, {
      ext: {
        binary: {},
      },
    });
    this.publish(channel, content, ext, publishCallback);
  };

  /**
   * Performs a remote call, a request with a response, to the given target with the given data.
   * The response returned by the server is notified the given callback function.
   * The invocation may specify a timeout in milliseconds, after which the call is aborted on
   * the client-side, causing a failed response to be passed to the given callback function.
   * @param target the remote call target
   * @param content the remote call content
   * @param timeout the remote call timeout, or 0 for no timeout
   * @param callProps an object to be merged with the remote call message
   * @param callback the function to be invoked with the response
   */
  this.remoteCall = function (target, content, timeout, callProps, callback) {
    if (arguments.length < 1) {
      throw "Illegal arguments number: required 1, got " + arguments.length;
    }
    if (!_isString(target)) {
      throw "Illegal argument type: target must be a string";
    }
    if (_isDisconnected()) {
      throw "Illegal state: disconnected";
    }

    if (_isFunction(content)) {
      callback = content;
      content = {};
      timeout = _config.maxNetworkDelay;
      callProps = undefined;
    } else if (_isFunction(timeout)) {
      callback = timeout;
      timeout = _config.maxNetworkDelay;
      callProps = undefined;
    } else if (_isFunction(callProps)) {
      callback = callProps;
      callProps = undefined;
    }

    if (typeof timeout !== "number") {
      throw "Illegal argument type: timeout must be a number";
    }

    if (!target.match(/^\//)) {
      target = "/" + target;
    }
    const channel = "/service" + target;
    if (!_isValidChannel(channel)) {
      throw "Illegal argument: invalid target " + target;
    }

    const bayeuxMessage = {
      id: _nextMessageId(),
      channel: channel,
      data: content,
    };
    const message = this._mixin(false, {}, callProps, bayeuxMessage);

    const context = {
      callback: callback,
    };
    if (timeout > 0) {
      context.timeout = _cometd.setTimeout(() => {
        _cometd._debug(
          "Timing out remote call",
          message,
          "after",
          timeout,
          "ms"
        );
        _failMessage({
          id: message.id,
          error: "406::timeout",
          successful: false,
          failure: {
            message: message,
            reason: "Remote Call Timeout",
          },
        });
      }, timeout);
      _cometd._debug(
        "Scheduled remote call timeout",
        message,
        "in",
        timeout,
        "ms"
      );
    }
    _remoteCalls[message.id] = context;

    _queueSend(message);
  };

  /**
   * Performs a remote call with binary data.
   * @param target the remote call target
   * @param data the remote call binary data
   * @param last whether the binary data chunk is the last
   * @param meta an object containing meta data associated to the binary chunk
   * @param timeout the remote call timeout, or 0 for no timeout
   * @param callProps an object to be merged with the remote call message
   * @param callback the function to be invoked with the response
   */
  this.remoteCallBinary = function (
    target,
    data,
    last,
    meta,
    timeout,
    callProps,
    callback
  ) {
    if (_isFunction(data)) {
      callback = data;
      data = new ArrayBuffer(0);
      last = true;
      meta = undefined;
      timeout = _config.maxNetworkDelay;
      callProps = undefined;
    } else if (_isFunction(last)) {
      callback = last;
      last = true;
      meta = undefined;
      timeout = _config.maxNetworkDelay;
      callProps = undefined;
    } else if (_isFunction(meta)) {
      callback = meta;
      meta = undefined;
      timeout = _config.maxNetworkDelay;
      callProps = undefined;
    } else if (_isFunction(timeout)) {
      callback = timeout;
      timeout = _config.maxNetworkDelay;
      callProps = undefined;
    } else if (_isFunction(callProps)) {
      callback = callProps;
      callProps = undefined;
    }

    const content = {
      meta: meta,
      data: data,
      last: last,
    };
    const ext = this._mixin(false, callProps, {
      ext: {
        binary: {},
      },
    });

    this.remoteCall(target, content, timeout, ext, callback);
  };

  /**
   * Returns a string representing the status of the bayeux communication with the Bayeux server.
   */
  this.getStatus = () => _status;

  /**
   * Returns whether this instance has been disconnected.
   */
  this.isDisconnected = _isDisconnected;

  /**
   * Sets the backoff period used to increase the backoff time when retrying an unsuccessful or failed message.
   * Default value is 1 second, which means if there is a persistent failure the retries will happen
   * after 1 second, then after 2 seconds, then after 3 seconds, etc. So for example with 15 seconds of
   * elapsed time, there will be 5 retries (at 1, 3, 6, 10 and 15 seconds elapsed).
   * @param period the backoff period to set
   * @see #getBackoffIncrement()
   */
  this.setBackoffIncrement = (period) => {
    _config.backoffIncrement = period;
  };

  /**
   * Returns the backoff period used to increase the backoff time when retrying an unsuccessful or failed message.
   * @see #setBackoffIncrement(period)
   */
  this.getBackoffIncrement = () => _config.backoffIncrement;

  /**
   * Returns the backoff period to wait before retrying an unsuccessful or failed message.
   */
  this.getBackoffPeriod = () => _backoff;

  /**
   * Increases the backoff period up to the maximum value configured.
   * @returns the backoff period after increment
   * @see getBackoffIncrement
   */
  this.increaseBackoffPeriod = () => _increaseBackoff();

  /**
   * Resets the backoff period to zero.
   */
  this.resetBackoffPeriod = () => {
    _resetBackoff();
  };

  /**
   * Sets the log level for console logging.
   * Valid values are the strings 'error', 'warn', 'info' and 'debug', from
   * less verbose to more verbose.
   * @param level the log level string
   */
  this.setLogLevel = (level) => {
    _config.logLevel = level;
  };

  /**
   * Registers an extension whose callbacks are called for every incoming message
   * (that comes from the server to this client implementation) and for every
   * outgoing message (that originates from this client implementation for the
   * server).
   * The format of the extension object is the following:
   * <pre>
   * {
   *     incoming: message => { ... },
   *     outgoing: message => { ... }
   * }
   * </pre>
   * Both properties are optional, but if they are present they will be called
   * respectively for each incoming message and for each outgoing message.
   * @param name the name of the extension
   * @param extension the extension to register
   * @return true if the extension was registered, false otherwise
   * @see #unregisterExtension(name)
   */
  this.registerExtension = function (name, extension) {
    if (arguments.length < 2) {
      throw "Illegal arguments number: required 2, got " + arguments.length;
    }
    if (!_isString(name)) {
      throw "Illegal argument type: extension name must be a string";
    }

    let existing = false;
    for (let i = 0; i < _extensions.length; ++i) {
      const existingExtension = _extensions[i];
      if (existingExtension.name === name) {
        existing = true;
        break;
      }
    }
    if (!existing) {
      _extensions.push({
        name: name,
        extension: extension,
      });
      this._debug("Registered extension", name);

      // Callback for extensions
      if (_isFunction(extension.registered)) {
        extension.registered(name, this);
      }

      return true;
    } else {
      this._info(
        "Could not register extension with name",
        name,
        "since another extension with the same name already exists"
      );
      return false;
    }
  };

  /**
   * Unregister an extension previously registered with
   * {@link #registerExtension(name, extension)}.
   * @param name the name of the extension to unregister.
   * @return true if the extension was unregistered, false otherwise
   */
  this.unregisterExtension = function (name) {
    if (!_isString(name)) {
      throw "Illegal argument type: extension name must be a string";
    }

    let unregistered = false;
    for (let i = 0; i < _extensions.length; ++i) {
      const extension = _extensions[i];
      if (extension.name === name) {
        _extensions.splice(i, 1);
        unregistered = true;
        this._debug("Unregistered extension", name);

        // Callback for extensions
        const ext = extension.extension;
        if (_isFunction(ext.unregistered)) {
          ext.unregistered();
        }

        break;
      }
    }
    return unregistered;
  };

  /**
   * Find the extension registered with the given name.
   * @param name the name of the extension to find
   * @return the extension found or null if no extension with the given name has been registered
   */
  this.getExtension = (name) => {
    for (let i = 0; i < _extensions.length; ++i) {
      const extension = _extensions[i];
      if (extension.name === name) {
        return extension.extension;
      }
    }
    return null;
  };

  /**
   * Returns the name assigned to this CometD object, or the string 'default'
   * if no name has been explicitly passed as parameter to the constructor.
   */
  this.getName = () => _name;

  /**
   * Returns the clientId assigned by the Bayeux server during handshake.
   */
  this.getClientId = () => _clientId;

  /**
   * Returns the URL of the Bayeux server.
   */
  this.getURL = () => {
    if (_transport) {
      let url = _transport.getURL();
      if (url) {
        return url;
      }
      url = _config.urls[_transport.getType()];
      if (url) {
        return url;
      }
    }
    return _config.url;
  };

  this.getTransport = () => _transport;

  this.getConfiguration = function () {
    return this._mixin(true, {}, _config);
  };

  this.getAdvice = function () {
    return this._mixin(true, {}, _advice);
  };

  this.setTimeout = (funktion, delay) =>
    _scheduler.setTimeout(() => {
      try {
        _cometd._debug("Invoking timed function", funktion);
        funktion();
      } catch (x) {
        _cometd._debug("Exception invoking timed function", funktion, x);
      }
    }, delay);

  this.clearTimeout = (id) => {
    _scheduler.clearTimeout(id);
  };

  // Initialize transports.
  if (window.WebSocket) {
    this.registerTransport("websocket", new WebSocketTransport());
  }
  this.registerTransport("long-polling", new LongPollingTransport());
  this.registerTransport("callback-polling", new CallbackPollingTransport());
}
