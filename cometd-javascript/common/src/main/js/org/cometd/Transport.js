;(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        // AMD Definition
        define(['./Utils'], factory);
    } else if (typeof require === 'function' && typeof exports === 'object') {
        // CommonJS
        module.exports = factory(require('./Utils'));
    } else {
        // Global Definition

        // Namespaces for the cometd implementation
        root.org = root.org || {};
        root.org.cometd = org.cometd || {};

        var namespace = root.org.cometd;
        namespace.Transport = factory(namespace.Utils);
    }
}(this, function (Utils) {
    'use strict';

    /**
     * Base object with the common functionality for transports.
     */
    var Transport = function() {
        var _type;
        var _cometd;
        var _url;

        /**
         * Function invoked just after a transport has been successfully registered.
         * @param type the type of transport (for example 'long-polling')
         * @param cometd the cometd object this transport has been registered to
         * @see #unregistered()
         */
        this.registered = function(type, cometd) {
            _type = type;
            _cometd = cometd;
        };

        /**
         * Function invoked just after a transport has been successfully unregistered.
         * @see #registered(type, cometd)
         */
        this.unregistered = function() {
            _type = null;
            _cometd = null;
        };

        this._debug = function() {
            _cometd._debug.apply(_cometd, arguments);
        };

        this._mixin = function() {
            return _cometd._mixin.apply(_cometd, arguments);
        };

        this.getConfiguration = function() {
            return _cometd.getConfiguration();
        };

        this.getAdvice = function() {
            return _cometd.getAdvice();
        };

        this.setTimeout = function(funktion, delay) {
            return Utils.setTimeout(_cometd, funktion, delay);
        };

        this.clearTimeout = function(handle) {
            Utils.clearTimeout(handle);
        };

        /**
         * Converts the given response into an array of bayeux messages
         * @param response the response to convert
         * @return an array of bayeux messages obtained by converting the response
         */
        this.convertToMessages = function(response) {
            if (Utils.isString(response)) {
                try {
                    return JSON.parse(response);
                } catch (x) {
                    this._debug('Could not convert to JSON the following string', '"' + response + '"');
                    throw x;
                }
            }
            if (Utils.isArray(response)) {
                return response;
            }
            if (response === undefined || response === null) {
                return [];
            }
            if (response instanceof Object) {
                return [response];
            }
            throw 'Conversion Error ' + response + ', typeof ' + (typeof response);
        };

        /**
         * Returns whether this transport can work for the given version and cross domain communication case.
         * @param version a string indicating the transport version
         * @param crossDomain a boolean indicating whether the communication is cross domain
         * @param url the URL to connect to
         * @return true if this transport can work for the given version and cross domain communication case,
         * false otherwise
         */
        this.accept = function(version, crossDomain, url) {
            throw 'Abstract';
        };

        /**
         * Returns the type of this transport.
         * @see #registered(type, cometd)
         */
        this.getType = function() {
            return _type;
        };

        this.getURL = function() {
            return _url;
        };

        this.setURL = function(url) {
            _url = url;
        };

        this.send = function(envelope, metaConnect) {
            throw 'Abstract';
        };

        this.reset = function(init) {
            this._debug('Transport', _type, 'reset', init ? 'initial' : 'retry');
        };

        this.abort = function() {
            this._debug('Transport', _type, 'aborted');
        };

        this.toString = function() {
            return this.getType();
        };
    };

    Transport.derive = function(baseObject) {
        function F() {
        }

        F.prototype = baseObject;
        return new F();
    };

    return Transport;
}));
