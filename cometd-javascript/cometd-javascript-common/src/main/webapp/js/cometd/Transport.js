import { Utils } from "./Utils.js";

/**
 * Base object with the common functionality for transports.
 */
export function Transport() {
  let _type;
  let _cometd;
  let _url;

  /**
   * Function invoked just after a transport has been successfully registered.
   * @param type the type of transport (for example 'long-polling')
   * @param cometd the cometd object this transport has been registered to
   * @see #unregistered()
   */
  this.registered = (type, cometd) => {
    _type = type;
    _cometd = cometd;
  };

  /**
   * Function invoked just after a transport has been successfully unregistered.
   * @see #registered(type, cometd)
   */
  this.unregistered = () => {
    _type = null;
    _cometd = null;
  };

  this._notifyTransportTimeout = function (messages) {
    const callbacks = _cometd._getTransportListeners("timeout");
    if (callbacks) {
      for (let i = 0; i < callbacks.length; ++i) {
        const listener = callbacks[i];
        try {
          const result = listener.call(this, messages);
          if (typeof result === "number" && result > 0) {
            return result;
          }
        } catch (x) {
          this._info(
            "Exception during execution of transport listener",
            listener,
            x
          );
        }
      }
    }
    return 0;
  };

  this._debug = function () {
    _cometd._debug.apply(_cometd, arguments);
  };

  this._info = function () {
    _cometd._info.apply(_cometd, arguments);
  };

  this._mixin = function () {
    return _cometd._mixin.apply(_cometd, arguments);
  };

  this.getConfiguration = () => _cometd.getConfiguration();

  this.getAdvice = () => _cometd.getAdvice();

  this.setTimeout = (funktion, delay) => _cometd.setTimeout(funktion, delay);

  this.clearTimeout = (id) => {
    _cometd.clearTimeout(id);
  };

  this.convertToJSON = function (messages) {
    const maxSize = this.getConfiguration().maxSendBayeuxMessageSize;
    let result = "[";
    for (let i = 0; i < messages.length; ++i) {
      if (i > 0) {
        result += ",";
      }
      const message = messages[i];
      const json = JSON.stringify(message);
      if (json.length > maxSize) {
        throw "maxSendBayeuxMessageSize " + maxSize + " exceeded";
      }
      result += json;
    }
    result += "]";
    return result;
  };

  /**
   * Converts the given response into an array of bayeux messages
   * @param response the response to convert
   * @return an array of bayeux messages obtained by converting the response
   */
  this.convertToMessages = function (response) {
    if (Utils.isString(response)) {
      try {
        return JSON.parse(response);
      } catch (x) {
        this._debug(
          "Could not convert to JSON the following string",
          '"' + response + '"'
        );
        throw x;
      }
    }
    if (Array.isArray(response)) {
      return response;
    }
    if (response === undefined || response === null) {
      return [];
    }
    if (response instanceof Object) {
      return [response];
    }
    throw "Conversion Error " + response + ", typeof " + typeof response;
  };

  /**
   * Returns whether this transport can work for the given version and cross domain communication case.
   * @param version a string indicating the transport version
   * @param crossDomain a boolean indicating whether the communication is cross domain
   * @param url the URL to connect to
   * @return true if this transport can work for the given version and cross domain communication case,
   * false otherwise
   */
  this.accept = (version, crossDomain, url) => {
    throw "Abstract";
  };

  /**
   * Returns the type of this transport.
   * @see #registered(type, cometd)
   */
  this.getType = () => _type;

  this.getURL = () => _url;

  this.setURL = (url) => {
    _url = url;
  };

  this.send = (envelope, metaConnect) => {
    throw "Abstract";
  };

  this.reset = function (init) {
    this._debug("Transport", _type, "reset", init ? "initial" : "retry");
  };

  this.abort = function () {
    this._debug("Transport", _type, "aborted");
  };

  this.toString = function () {
    return this.getType();
  };
}

Transport.derive = (baseObject) => {
  function F() {}

  F.prototype = baseObject;
  return new F();
};
