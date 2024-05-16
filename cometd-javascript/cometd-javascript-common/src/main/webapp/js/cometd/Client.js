/*
 * Copyright (c) 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {TransportRegistry} from "./TransportRegistry.js";
import {CallbackPollingTransport} from "./CallbackPollingTransport.js";
import {LongPollingTransport} from "./LongPollingTransport.js";
import {WebSocketTransport} from "./WebSocketTransport.js";

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
 * The default name is the string "default".
 * @param name the optional name of this cometd object
 */
export class CometD {
    #scheduler = new Scheduler();
    #name;
    #crossDomain = false;
    #transports = new TransportRegistry();
    #transport;
    #status = "disconnected";
    #messageId = 0;
    #clientId = null;
    #batch = 0;
    #messageQueue = [];
    #internalBatch = false;
    #listenerId = 0;
    #listeners = {};
    #transportListeners = {};
    #backoff = 0;
    #scheduledSend = null;
    #extensions = [];
    #advice = {};
    #handshakeProps;
    #handshakeCallback;
    #callbacks = {};
    #remoteCalls = {};
    #reestablish = false;
    #connected = false;
    #unconnectTime = 0;
    #handshakeMessages = 0;
    #metaConnect = null;
    #config = {
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
            maxInterval: 0
        }
    };

    constructor(name) {
        this.#name = name || "default";
        // Initialize transports.
        if (window.WebSocket) {
            this.registerTransport("websocket", new WebSocketTransport());
        }
        this.registerTransport("long-polling", new LongPollingTransport());
        this.registerTransport("callback-polling", new CallbackPollingTransport());
    }

    static #fieldValue(object, name) {
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
    _mixin(deep, target, objects) {
        const result = target || {};

        // Skip first 2 parameters (deep and target), and loop over the others.
        for (let i = 2; i < arguments.length; ++i) {
            const object = arguments[i];

            if (object === undefined || object === null) {
                continue;
            }

            for (let propName in object) {
                if (object.hasOwnProperty(propName)) {
                    const prop = CometD.#fieldValue(object, propName);
                    const targ = CometD.#fieldValue(result, propName);

                    // Avoid infinite loops.
                    if (prop === target) {
                        continue;
                    }
                    // Do not mixin undefined values.
                    if (prop === undefined) {
                        continue;
                    }

                    if (deep && typeof prop === "object" && prop !== null) {
                        if (prop instanceof Array) {
                            result[propName] = this._mixin(deep, targ instanceof Array ? targ : [], prop);
                        } else {
                            const source = typeof targ === "object" && !(targ instanceof Array) ? targ : {};
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

    static #isString(value) {
        if (value === undefined || value === null) {
            return false;
        }
        return typeof value === "string" || value instanceof String;
    }

    static #isAlpha(char) {
        if (char >= "A" && char <= "Z") {
            return true;
        }
        return char >= "a" && char <= "z";
    }

    static #isNumeric(char) {
        return char >= "0" && char <= "9";
    }

    static #isAllowed(char) {
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

    static #isValidChannel(value) {
        if (!CometD.#isString(value)) {
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
            if (CometD.#isAlpha(char) || CometD.#isNumeric(char) || CometD.#isAllowed(char)) {
                continue;
            }
            return false;
        }
        return true;
    }

    static #isFunction(value) {
        if (value === undefined || value === null) {
            return false;
        }
        return typeof value === "function";
    }

    static #zeroPad(value, length) {
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

    #log(level, args) {
        if (window.console) {
            const logger = window.console[level];
            if (CometD.#isFunction(logger)) {
                const now = new Date();
                [].splice.call(args, 0, 0, CometD.#zeroPad(now.getHours(), 2) + ":" + CometD.#zeroPad(now.getMinutes(), 2) + ":" +
                    CometD.#zeroPad(now.getSeconds(), 2) + "." + CometD.#zeroPad(now.getMilliseconds(), 3));
                logger.apply(window.console, args);
            }
        }
    }

    _warn() {
        this.#log("warn", arguments);
    };

    _info() {
        if (this.#config.logLevel !== "warn") {
            this.#log("info", arguments);
        }
    };

    _debug() {
        if (this.#config.logLevel === "debug") {
            this.#log("debug", arguments);
        }
    };

    static #splitURL(url) {
        // [1] = protocol://,
        // [2] = host:port,
        // [3] = host,
        // [4] = IPv6_host,
        // [5] = IPv4_host,
        // [6] = :port,
        // [7] = port,
        // [8] = uri,
        // [9] = rest (query / fragment)
        return new RegExp("(^https?://)?(((\\[[^\\]]+])|([^:/?#]+))(:(\\d+))?)?([^?#]*)(.*)?").exec(url);
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
    #isCrossDomain(hostAndPort) {
        if (window.location && window.location.host) {
            if (hostAndPort) {
                return hostAndPort !== window.location.host;
            }
        }
        return false;
    };

    #configure(configuration) {
        this._debug("Configuring cometd object with", configuration);
        // Support old style param, where only the Bayeux server URL was passed.
        if (CometD.#isString(configuration)) {
            configuration = {
                url: configuration
            };
        }
        if (!configuration) {
            configuration = {};
        }

        this.#config = this._mixin(false, this.#config, configuration);

        const url = this.getURL();
        if (!url) {
            throw "Missing required configuration parameter 'url' specifying the Bayeux server URL";
        }

        // Check if we're cross domain.
        const urlParts = CometD.#splitURL(url);
        const hostAndPort = urlParts[2];
        const uri = urlParts[8];
        const afterURI = urlParts[9];
        this.#crossDomain = this.#isCrossDomain(hostAndPort);

        // Check if appending extra path is supported.
        if (this.#config.appendMessageTypeToURL) {
            if (afterURI !== undefined && afterURI.length > 0) {
                this._info("Appending message type to URI", uri, afterURI, "is not supported, disabling 'appendMessageTypeToURL' configuration");
                this.#config.appendMessageTypeToURL = false;
            } else {
                const uriSegments = uri.split("/");
                let lastSegmentIndex = uriSegments.length - 1;
                if (uri.match(/\/$/)) {
                    lastSegmentIndex -= 1;
                }
                if (uriSegments[lastSegmentIndex].indexOf(".") >= 0) {
                    // Very likely the CometD servlet's URL pattern is mapped to an extension,
                    // such as *.cometd, so cannot add the extra path in this case.
                    this._info("Appending message type to URI", uri, "is not supported, disabling 'appendMessageTypeToURL' configuration");
                    this.#config.appendMessageTypeToURL = false;
                }
            }
        }

        if (window.Worker && window.Blob && window.URL && this.#config.useWorkerScheduler) {
            let code = WorkerScheduler.toString();
            // Remove the function declaration, the opening brace and the closing brace.
            code = code.substring(code.indexOf("{") + 1, code.lastIndexOf("}"));
            const blob = new window.Blob([code], {
                type: "application/json"
            });
            const blobURL = window.URL.createObjectURL(blob);
            const worker = new window.Worker(blobURL);
            // Replace setTimeout() and clearTimeout() with the worker implementation.
            this.#scheduler.setTimeout = (funktion, delay) => {
                const id = this.#scheduler.register(funktion);
                worker.postMessage({
                    id: id,
                    type: "setTimeout",
                    delay: delay
                });
                return id;
            };
            this.#scheduler.clearTimeout = (id) => {
                this.#scheduler.unregister(id);
                worker.postMessage({
                    id: id,
                    type: "clearTimeout",
                });
            };
            worker.onmessage = (e) => {
                const id = e.data.id;
                const funktion = this.#scheduler.unregister(id);
                if (funktion) {
                    funktion();
                }
            };
        }
    }

    #removeListener(subscription) {
        if (subscription) {
            const subscriptions = this.#listeners[subscription.channel];
            if (subscriptions && subscriptions[subscription.id]) {
                delete subscriptions[subscription.id];
                this._debug("Removed", subscription.listener ? "listener" : "subscription", subscription);
            }
        }
    }

    #removeSubscription(subscription) {
        if (subscription && !subscription.listener) {
            this.#removeListener(subscription);
        }
    }

    #clearSubscriptions() {
        for (let channel in this.#listeners) {
            if (this.#listeners.hasOwnProperty(channel)) {
                const subscriptions = this.#listeners[channel];
                if (subscriptions) {
                    for (let id in subscriptions) {
                        if (subscriptions.hasOwnProperty(id)) {
                            this.#removeSubscription(subscriptions[id]);
                        }
                    }
                }
            }
        }
    }

    #setStatus(newStatus) {
        const oldStatus = this.getStatus();
        if (oldStatus !== newStatus) {
            this._debug("Status", oldStatus, "->", newStatus);
            this.#status = newStatus;
        }
    }

    #isDisconnected() {
        const status = this.#status;
        return status === "disconnecting" || status === "disconnected";
    }

    #nextMessageId() {
        const result = ++this.#messageId;
        return "" + result;
    }

    #applyExtension(scope, callback, name, message, outgoing) {
        try {
            return callback.call(scope, message);
        } catch (x) {
            const handler = this.onExtensionException;
            if (CometD.#isFunction(handler)) {
                this._debug("Invoking extension exception handler", name, x);
                try {
                    handler.call(this, x, name, outgoing, message);
                } catch (xx) {
                    this._info("Exception during execution of extension exception handler", name, xx);
                }
            } else {
                this._info("Exception during execution of extension", name, x);
            }
            return message;
        }
    }

    #applyIncomingExtensions(message) {
        for (let i = 0; i < this.#extensions.length; ++i) {
            if (message === undefined || message === null) {
                break;
            }

            const extension = this.#extensions[i];
            const callback = extension.extension.incoming;
            if (CometD.#isFunction(callback)) {
                const result = this.#applyExtension(extension.extension, callback, extension.name, message, false);
                message = result === undefined ? message : result;
            }
        }
        return message;
    }

    #applyOutgoingExtensions(message) {
        for (let i = this.#extensions.length - 1; i >= 0; --i) {
            if (message === undefined || message === null) {
                break;
            }

            const extension = this.#extensions[i];
            const callback = extension.extension.outgoing;
            if (CometD.#isFunction(callback)) {
                const result = this.#applyExtension(extension.extension, callback, extension.name, message, true);
                message = result === undefined ? message : result;
            }
        }
        return message;
    }

    #notify(channel, message) {
        const subscriptions = this.#listeners[channel];
        if (subscriptions) {
            for (let id in subscriptions) {
                if (subscriptions.hasOwnProperty(id)) {
                    const subscription = subscriptions[id];
                    // Subscriptions may come and go, so the array may have holes.
                    if (subscription) {
                        try {
                            subscription.callback.call(subscription.scope, message);
                        } catch (x) {
                            const handler = this.onListenerException;
                            if (CometD.#isFunction(handler)) {
                                this._debug("Invoking listener exception handler", subscription, x);
                                try {
                                    handler.call(this, x, subscription, subscription.listener, message);
                                } catch (xx) {
                                    this._info("Exception during execution of listener exception handler", subscription, xx);
                                }
                            } else {
                                this._info("Exception during execution of listener", subscription, message, x);
                            }
                        }
                    }
                }
            }
        }
    }

    #notifyListeners(channel, message) {
        // Notify direct listeners
        this.#notify(channel, message);

        // Notify the globbing listeners
        const channelParts = channel.split("/");
        const last = channelParts.length - 1;
        for (let i = last; i > 0; --i) {
            let channelPart = channelParts.slice(0, i).join("/") + "/*";
            // We don't want to notify /foo/* if the channel is /foo/bar/baz,
            // so we stop at the first non-recursive globbing.
            if (i === last) {
                this.#notify(channelPart, message);
            }
            // Add the recursive globber and notify.
            channelPart += "*";
            this.#notify(channelPart, message);
        }
    }

    #cancelDelayedSend() {
        if (this.#scheduledSend !== null) {
            this.clearTimeout(this.#scheduledSend);
        }
        this.#scheduledSend = null;
    }

    #delayedSend(operation, delay) {
        this.#cancelDelayedSend();
        const time = this.#advice.interval + delay;
        this._debug("Function scheduled in", time, "ms, interval =", this.#advice.interval, "backoff =", this.#backoff, operation);
        this.#scheduledSend = this.setTimeout(operation, time);
    }

    /**
     * Delivers the messages to the CometD server
     * @param messages the array of messages to send
     * @param metaConnect true if this send is on /meta/connect
     * @param extraPath an extra path to append to the Bayeux server URL
     */
    #send(messages, metaConnect, extraPath) {
        // We must be sure that the messages have a clientId.
        // This is not guaranteed since the handshake may take time to return
        // (and hence the clientId is not known yet) and the application
        // may create other messages.
        for (let i = 0; i < messages.length; ++i) {
            let message = messages[i];
            const messageId = message.id;

            const clientId = this.getClientId();
            if (clientId) {
                message.clientId = clientId;
            }

            message = this.#applyOutgoingExtensions(message);
            if (message !== undefined && message !== null) {
                // Extensions may have modified the message id, but we need to own it.
                message.id = messageId;
                messages[i] = message;
            } else {
                delete this.#callbacks[messageId];
                messages.splice(i--, 1);
            }
        }

        if (messages.length === 0) {
            return;
        }

        if (metaConnect) {
            this.#metaConnect = messages[0];
        }

        let url = this.getURL();
        if (this.#config.appendMessageTypeToURL) {
            // If url does not end with "/", then append it
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
                    this.#handleMessages(rcvdMessages);
                } catch (x) {
                    this._info("Exception during handling of messages", x);
                }
            },
            onFailure: (conduit, messages, failure) => {
                try {
                    const transport = this.getTransport();
                    failure.connectionType = transport ? transport.type : "unknown";
                    this.#handleFailure(conduit, messages, failure);
                } catch (x) {
                    this._info("Exception during handling of failure", x);
                }
            },
        };
        this._debug("Send", envelope);
        this.getTransport().send(envelope, metaConnect);
    }

    #queueSend(message) {
        if (this.#batch > 0 || this.#internalBatch === true) {
            this.#messageQueue.push(message);
        } else {
            this.#send([message], false);
        }
    }

    /**
     * Sends a complete bayeux message.
     * This method is exposed as a public so that extensions may use it
     * to send bayeux message directly, for example in case of re-sending
     * messages that have already been sent but that for some reason must
     * be resent.
     */
    send(message) {
        this.#queueSend(message);
    }

    #resetBackoff() {
        this.#backoff = 0;
    }

    #increaseBackoff() {
        if (this.#backoff < this.#config.maxBackoff) {
            this.#backoff += this.getBackoffIncrement();
        }
        return this.#backoff;
    }

    /**
     * Starts the batch of messages to be sent in a single request.
     */
    #startBatch() {
        ++this.#batch;
        this._debug("Starting batch, depth", this.#batch);
    }

    #flushBatch() {
        const messages = this.#messageQueue;
        this.#messageQueue = [];
        if (messages.length > 0) {
            this.#send(messages, false);
        }
    }

    /**
     * Ends the batch of messages to be sent in a single request,
     * optionally sending messages present in the message queue depending
     * on the given argument.
     */
    #endBatch() {
        --this.#batch;
        this._debug("Ending batch, depth", this.#batch);
        if (this.#batch < 0) {
            throw "Calls to startBatch() and endBatch() are not paired";
        }

        if (this.#batch === 0 && !this.isDisconnected() && !this.#internalBatch) {
            this.#flushBatch();
        }
    }

    /**
     * Sends the connect message
     */
    #connect() {
        if (!this.isDisconnected()) {
            const bayeuxMessage = {
                id: this.#nextMessageId(),
                channel: "/meta/connect",
                connectionType: this.getTransport().type,
            };

            // In case of reload or temporary loss of connection
            // we want the next successful connect to return immediately
            // instead of being held by the server, so that connect listeners
            // can be notified that the connection has been re-established
            if (!this.#connected) {
                bayeuxMessage.advice = {
                    timeout: 0,
                };
            }

            this.#setStatus("connecting");
            this._debug("Connect sent", bayeuxMessage);
            this.#send([bayeuxMessage], true, "connect");
            this.#setStatus("connected");
        }
    }

    #delayedConnect(delay) {
        this.#setStatus("connecting");
        this.#delayedSend(() => {
            this.#connect();
        }, delay);
    }

    #updateAdvice(newAdvice) {
        if (newAdvice) {
            this.#advice = this._mixin(false, {}, this.#config.advice, newAdvice);
            this._debug("New advice", this.#advice);
        }
    }

    #disconnect(abort) {
        this.#cancelDelayedSend();
        const transport = this.getTransport();
        if (abort && transport) {
            transport.abort();
        }
        this.#crossDomain = false;
        this.#transport = null;
        this.#setStatus("disconnected");
        this.#clientId = null;
        this.#batch = 0;
        this.#resetBackoff();
        this.#reestablish = false;
        this.#connected = false;
        this.#unconnectTime = 0;
        this.#metaConnect = null;

        // Fail any existing queued message
        if (this.#messageQueue.length > 0) {
            const messages = this.#messageQueue;
            this.#messageQueue = [];
            this.#handleFailure(undefined, messages, {
                reason: "Disconnected",
            });
        }
    }

    #notifyTransportException(oldTransport, newTransport, failure) {
        const handler = this.onTransportException;
        if (CometD.#isFunction(handler)) {
            this._debug("Invoking transport exception handler", oldTransport, newTransport, failure);
            try {
                handler.call(this, failure, oldTransport, newTransport);
            } catch (x) {
                this._info("Exception during execution of transport exception handler", x);
            }
        }
    }

    /**
     * Sends the initial handshake message
     */
    #handshake(handshakeProps, handshakeCallback) {
        if (CometD.#isFunction(handshakeProps)) {
            handshakeCallback = handshakeProps;
            handshakeProps = undefined;
        }

        this.#clientId = null;

        this.clearSubscriptions();

        // Reset the transports if we're not retrying the handshake
        if (this.isDisconnected()) {
            this.#transports.reset(true);
        }

        // Reset the advice.
        this.#updateAdvice({});

        this.#batch = 0;

        // Mark the start of an internal batch.
        // This is needed because handshake and connect are async.
        // It may happen that the application calls init() then subscribe()
        // and the subscribe message is sent before the connect message, if
        // the subscribe message is not held until the connect message is sent.
        // So here we start a batch to hold temporarily any message until
        // the connection is fully established.
        this.#internalBatch = true;

        // Save the properties provided by the user, so that
        // we can reuse them during automatic re-handshake
        this.#handshakeProps = handshakeProps;
        this.#handshakeCallback = handshakeCallback;

        const version = "1.0";

        // Figure out the transports to send to the server
        const url = this.getURL();
        const transportTypes = this.#transports.findTransportTypes(version, this.#crossDomain, url);

        const bayeuxMessage = {
            id: this.#nextMessageId(),
            version: version,
            minimumVersion: version,
            channel: "/meta/handshake",
            supportedConnectionTypes: transportTypes,
            advice: {
                timeout: this.#advice.timeout,
                interval: this.#advice.interval
            }
        };
        // Do not allow the user to override important fields.
        const message = this._mixin(false, {}, this.#handshakeProps, bayeuxMessage);

        // Save the callback.
        this._putCallback(message.id, handshakeCallback);

        // Pick up the first available transport as initial transport
        // since we don't know if the server supports it
        if (!this.#transport) {
            this.#transport = this.#transports.negotiateTransport(transportTypes, version, this.#crossDomain, url);
            if (!this.#transport) {
                const failure = "Could not find initial transport among: " + this.getTransportTypes();
                this._warn(failure);
                throw failure;
            }
        }

        this._debug("Initial transport is", this.#transport.type);

        // We started a batch to hold the application messages,
        // so here we must bypass it and send immediately.
        this.#setStatus("handshaking");
        this._debug("Handshake sent", message);
        this.#send([message], false, "handshake");
    }

    #delayedHandshake(delay) {
        this.#setStatus("handshaking");

        // We will call #handshake() which will reset #clientId, but we want to avoid
        // that between the end of this method and the call to #handshake() someone may
        // call publish() (or other methods that call #queueSend()).
        this.#internalBatch = true;

        this.#delayedSend(() => {
            this.#handshake(this.#handshakeProps, this.#handshakeCallback);
        }, delay);
    }

    #notifyCallback(callback, message) {
        try {
            callback.call(this, message);
        } catch (x) {
            const handler = this.onCallbackException;
            if (CometD.#isFunction(handler)) {
                this._debug("Invoking callback exception handler", x);
                try {
                    handler.call(this, x, message);
                } catch (xx) {
                    this._info("Exception during execution of callback exception handler", xx);
                }
            } else {
                this._info("Exception during execution of message callback", x);
            }
        }
    }

    _getCallback(messageId) {
        return this.#callbacks[messageId];
    }

    _putCallback(messageId, callback) {
        const result = this._getCallback(messageId);
        if (CometD.#isFunction(callback)) {
            this.#callbacks[messageId] = callback;
        }
        return result;
    };

    #handleCallback(message) {
        const callback = this._getCallback([message.id]);
        if (CometD.#isFunction(callback)) {
            delete this.#callbacks[message.id];
            this.#notifyCallback(callback, message);
        }
    }

    #handleRemoteCall(message) {
        const context = this.#remoteCalls[message.id];
        delete this.#remoteCalls[message.id];
        if (context) {
            this._debug("Handling remote call response for", message, "with context", context);

            // Clear the timeout, if present.
            const timeout = context.timeout;
            if (timeout) {
                this.clearTimeout(timeout);
            }

            const callback = context.callback;
            if (CometD.#isFunction(callback)) {
                this.#notifyCallback(callback, message);
                return true;
            }
        }
        return false;
    }

    onTransportFailure(message, failureInfo, failureHandler) {
        this._debug("Transport failure", failureInfo, "for", message);

        const transports = this.getTransportRegistry();
        const url = this.getURL();
        const crossDomain = this.#isCrossDomain(CometD.#splitURL(url)[2]);
        const version = "1.0";
        const transportTypes = transports.findTransportTypes(version, crossDomain, url);

        if (failureInfo.action === "none") {
            if (message.channel === "/meta/handshake") {
                if (!failureInfo.transport) {
                    const failure = "Could not negotiate transport, client=[" + transportTypes + "], server=[" + message.supportedConnectionTypes + "]";
                    this._warn(failure);
                    const transport = this.getTransport();
                    if (transport) {
                        const transportType = transport.type;
                        this.#notifyTransportException(transportType, null, {
                            reason: failure,
                            connectionType: transportType,
                            transport: transport
                        });
                    }
                }
            }
        } else {
            failureInfo.delay = this.getBackoffPeriod();
            // Different logic depending on whether we are handshaking or connecting.
            if (message.channel === "/meta/handshake") {
                if (!failureInfo.transport) {
                    // The transport is invalid, try to negotiate again.
                    const oldTransportType = this.#transport ? this.#transport.type : null;
                    const newTransport = transports.negotiateTransport(transportTypes, version, crossDomain, url);
                    if (!newTransport) {
                        this._warn("Could not negotiate transport, client=[" + transportTypes + "]");
                        this.#notifyTransportException(oldTransportType, null, message.failure);
                        failureInfo.action = "none";
                    } else {
                        const newTransportType = newTransport.type;
                        this._debug("Transport", oldTransportType, "->", newTransportType);
                        this.#notifyTransportException(oldTransportType, newTransportType, message.failure);
                        failureInfo.action = "handshake";
                        failureInfo.transport = newTransport;
                    }
                }

                if (failureInfo.action !== "none") {
                    this.increaseBackoffPeriod();
                }
            } else {
                const now = new Date().getTime();

                if (this.#unconnectTime === 0) {
                    this.#unconnectTime = now;
                }

                if (failureInfo.action === "retry") {
                    failureInfo.delay = this.increaseBackoffPeriod();
                    // Check whether we may switch to handshaking.
                    const maxInterval = this.#advice.maxInterval;
                    if (maxInterval > 0) {
                        const expiration = this.#advice.timeout + this.#advice.interval + maxInterval;
                        const unconnected = now - this.#unconnectTime;
                        if (unconnected + this.#backoff > expiration) {
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

        failureHandler.call(this, failureInfo);
    };

    #handleTransportFailure(failureInfo) {
        this._debug("Transport failure handling", failureInfo);

        if (failureInfo.transport) {
            this.#transport = failureInfo.transport;
        }

        if (failureInfo.url) {
            this.#transport.url = failureInfo.url;
        }

        const action = failureInfo.action;
        const delay = failureInfo.delay || 0;
        switch (action) {
            case "handshake":
                this.#delayedHandshake(delay);
                break;
            case "retry":
                this.#delayedConnect(delay);
                break;
            case "none":
                this.#disconnect(true);
                break;
            default:
                throw "Unknown action " + action;
        }
    }

    #failHandshake(message, failureInfo) {
        this.#handleCallback(message);
        this.#notifyListeners("/meta/handshake", message);
        this.#notifyListeners("/meta/unsuccessful", message);

        // The listeners may have disconnected.
        if (this.isDisconnected()) {
            failureInfo.action = "none";
        }

        this.onTransportFailure(message, failureInfo, (failureInfo) => this.#handleTransportFailure(failureInfo));
    }

    #handshakeResponse(message) {
        const url = this.getURL();
        if (message.successful) {
            const crossDomain = this.#isCrossDomain(CometD.#splitURL(url)[2]);
            const newTransport = this.#transports.negotiateTransport(message.supportedConnectionTypes, message.version, crossDomain, url);
            if (newTransport === null) {
                message.successful = false;
                this.#failHandshake(message, {
                    cause: "negotiation",
                    action: "none",
                    transport: null
                });
                return;
            } else if (this.#transport !== newTransport) {
                this._debug("Transport", this.#transport && this.#transport.type, "->", newTransport.type);
                this.#transport = newTransport;
            }

            this.#clientId = message.clientId;

            // End the internal batch and allow held messages from the application
            // to go to the server (see #handshake() where we start the internal batch).
            this.#internalBatch = false;
            this.#flushBatch();

            // Here the new transport is in place, as well as the clientId, so
            // the listeners can perform a publish() if they want.
            // Notify the listeners before the connect below.
            message.reestablish = this.#reestablish;
            this.#reestablish = true;

            this.#handleCallback(message);
            this.#notifyListeners("/meta/handshake", message);

            this.#handshakeMessages = message["x-messages"] || 0;

            const action = this.isDisconnected() ? "none" : this.#advice.reconnect || "retry";
            switch (action) {
                case "retry":
                    this.#resetBackoff();
                    if (this.#handshakeMessages === 0) {
                        this.#delayedConnect(0);
                    } else {
                        this._debug("Processing", this.#handshakeMessages, "handshake-delivered messages");
                    }
                    break;
                case "none":
                    this.#disconnect(true);
                    break;
                default:
                    throw "Unrecognized advice action " + action;
            }
        } else {
            this.#failHandshake(message, {
                cause: "unsuccessful",
                action: this.#advice.reconnect || "handshake",
                transport: this.getTransport()
            });
        }
    }

    #handshakeFailure(message) {
        this.#failHandshake(message, {
            cause: "failure",
            action: "handshake",
            transport: null
        });
    }

    #matchMetaConnect(connect) {
        if (this.getStatus() === "disconnected") {
            return true;
        }
        if (this.#metaConnect && this.#metaConnect.id === connect.id) {
            this.#metaConnect = null;
            return true;
        }
        return false;
    }

    #failConnect(message, failureInfo) {
        // Notify the listeners after the status change but before the next action.
        this.#notifyListeners("/meta/connect", message);
        this.#notifyListeners("/meta/unsuccessful", message);

        // The listeners may have disconnected.
        if (this.isDisconnected()) {
            failureInfo.action = "none";
        }

        this.onTransportFailure(message, failureInfo, (failureInfo) => this.#handleTransportFailure(failureInfo));
    }

    #connectResponse(message) {
        if (this.#matchMetaConnect(message)) {
            this.#connected = message.successful;
            if (this.#connected) {
                this.#notifyListeners("/meta/connect", message);

                // Normally, the advice will say "reconnect: "retry", interval: 0"
                // and the server will hold the request, so when a response returns
                // we immediately call the server again (long polling).
                // Listeners can call disconnect(), so check the state after they run.
                const action = this.isDisconnected() ? "none" : this.#advice.reconnect || "retry";
                switch (action) {
                    case "retry":
                        this.#resetBackoff();
                        this.#delayedConnect(this.#backoff);
                        break;
                    case "none":
                        this.#disconnect(false);
                        break;
                    default:
                        throw "Unrecognized advice action " + action;
                }
            } else {
                this.#failConnect(message, {
                    cause: "unsuccessful",
                    action: this.#advice.reconnect || "retry",
                    transport: this.getTransport()
                });
            }
        } else {
            this._debug("Mismatched /meta/connect reply", message);
        }
    }

    #connectFailure(message) {
        if (this.#matchMetaConnect(message)) {
            this.#connected = false;
            this.#failConnect(message, {
                cause: "failure",
                action: "retry",
                transport: null
            });
        } else {
            this._debug("Mismatched /meta/connect failure", message);
        }
    }

    #failDisconnect(message) {
        this.#disconnect(true);
        this.#handleCallback(message);
        this.#notifyListeners("/meta/disconnect", message);
        this.#notifyListeners("/meta/unsuccessful", message);
    }

    #disconnectResponse(message) {
        if (message.successful) {
            // Wait for the /meta/connect to arrive.
            this.#disconnect(false);
            this.#handleCallback(message);
            this.#notifyListeners("/meta/disconnect", message);
        } else {
            this.#failDisconnect(message);
        }
    }

    #disconnectFailure(message) {
        this.#failDisconnect(message);
    }

    #failSubscribe(message) {
        const subscriptions = this.#listeners[message.subscription];
        if (subscriptions) {
            for (let id in subscriptions) {
                if (subscriptions.hasOwnProperty(id)) {
                    const subscription = subscriptions[id];
                    if (subscription && !subscription.listener) {
                        delete subscriptions[id];
                        this._debug("Removed failed subscription", subscription);
                    }
                }
            }
        }
        this.#handleCallback(message);
        this.#notifyListeners("/meta/subscribe", message);
        this.#notifyListeners("/meta/unsuccessful", message);
    }

    #subscribeResponse(message) {
        if (message.successful) {
            this.#handleCallback(message);
            this.#notifyListeners("/meta/subscribe", message);
        } else {
            this.#failSubscribe(message);
        }
    }

    #subscribeFailure(message) {
        this.#failSubscribe(message);
    }

    #failUnsubscribe(message) {
        this.#handleCallback(message);
        this.#notifyListeners("/meta/unsubscribe", message);
        this.#notifyListeners("/meta/unsuccessful", message);
    }

    #unsubscribeResponse(message) {
        if (message.successful) {
            this.#handleCallback(message);
            this.#notifyListeners("/meta/unsubscribe", message);
        } else {
            this.#failUnsubscribe(message);
        }
    }

    #unsubscribeFailure(message) {
        this.#failUnsubscribe(message);
    }

    #failMessage(message) {
        if (!this.#handleRemoteCall(message)) {
            this.#handleCallback(message);
            this.#notifyListeners("/meta/publish", message);
            this.#notifyListeners("/meta/unsuccessful", message);
        }
    }

    #messageResponse(message) {
        if (message.data !== undefined) {
            if (!this.#handleRemoteCall(message)) {
                this.#notifyListeners(message.channel, message);
                if (this.#handshakeMessages > 0) {
                    --this.#handshakeMessages;
                    if (this.#handshakeMessages === 0) {
                        this._debug("Processed last handshake-delivered message");
                        this.#delayedConnect(0);
                    }
                }
            }
        } else {
            if (message.successful === undefined) {
                this._warn("Unknown Bayeux Message", message);
            } else {
                if (message.successful) {
                    this.#handleCallback(message);
                    this.#notifyListeners("/meta/publish", message);
                } else {
                    this.#failMessage(message);
                }
            }
        }
    }

    #messageFailure(failure) {
        this.#failMessage(failure);
    }

    #receive(message) {
        this.#unconnectTime = 0;

        message = this.#applyIncomingExtensions(message);
        if (message === undefined || message === null) {
            return;
        }

        this.#updateAdvice(message.advice);

        const channel = message.channel;
        switch (channel) {
            case "/meta/handshake":
                this.#handshakeResponse(message);
                break;
            case "/meta/connect":
                this.#connectResponse(message);
                break;
            case "/meta/disconnect":
                this.#disconnectResponse(message);
                break;
            case "/meta/subscribe":
                this.#subscribeResponse(message);
                break;
            case "/meta/unsubscribe":
                this.#unsubscribeResponse(message);
                break;
            default:
                this.#messageResponse(message);
                break;
        }
    }

    /**
     * Receives a message.
     * This method is exposed as a public so that extensions may inject
     * messages simulating that they had been received.
     */
    receive(message) {
        this.#receive(message);
    }

    #handleMessages(rcvdMessages) {
        this._debug("Received", rcvdMessages);

        for (let i = 0; i < rcvdMessages.length; ++i) {
            const message = rcvdMessages[i];
            this.receive(message);
        }
    };

    #handleFailure(conduit, messages, failure) {
        this._debug("handleFailure", conduit, messages, failure);

        failure.transport = conduit;
        for (let i = 0; i < messages.length; ++i) {
            const message = messages[i];
            const failureMessage = {
                id: message.id,
                successful: false,
                channel: message.channel,
                failure: failure
            };
            failure.message = message;
            switch (message.channel) {
                case "/meta/handshake":
                    this.#handshakeFailure(failureMessage);
                    break;
                case "/meta/connect":
                    this.#connectFailure(failureMessage);
                    break;
                case "/meta/disconnect":
                    this.#disconnectFailure(failureMessage);
                    break;
                case "/meta/subscribe":
                    failureMessage.subscription = message.subscription;
                    this.#subscribeFailure(failureMessage);
                    break;
                case "/meta/unsubscribe":
                    failureMessage.subscription = message.subscription;
                    this.#unsubscribeFailure(failureMessage);
                    break;
                default:
                    this.#messageFailure(failureMessage);
                    break;
            }
        }
    };

    #hasSubscriptions(channel) {
        const subscriptions = this.#listeners[channel];
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

    #resolveScopedCallback(scope, callback) {
        const delegate = {
            scope: scope,
            method: callback,
        };
        if (CometD.#isFunction(scope)) {
            delegate.scope = undefined;
            delegate.method = scope;
        } else {
            if (CometD.#isString(callback)) {
                if (!scope) {
                    throw "Invalid scope " + scope;
                }
                delegate.method = scope[callback];
                if (!CometD.#isFunction(delegate.method)) {
                    throw "Invalid callback " + callback + " for scope " + scope;
                }
            } else if (!CometD.#isFunction(callback)) {
                throw "Invalid callback " + callback;
            }
        }
        return delegate;
    }

    #addListener(channel, scope, callback, isListener) {
        // The data structure is a map<channel, subscription[]>, where each subscription
        // holds the callback to be called and its scope.

        const delegate = this.#resolveScopedCallback(scope, callback);
        this._debug("Adding", isListener ? "listener" : "subscription", "on", channel, "with scope", delegate.scope, "and callback", delegate.method);

        const id = ++this.#listenerId;
        const subscription = {
            id: id,
            channel: channel,
            scope: delegate.scope,
            callback: delegate.method,
            listener: isListener
        };

        let subscriptions = this.#listeners[channel];
        if (!subscriptions) {
            subscriptions = {};
            this.#listeners[channel] = subscriptions;
        }

        subscriptions[id] = subscription;

        this._debug("Added", isListener ? "listener" : "subscription", subscription);

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
     */
    registerTransport(type, transport, index) {
        const result = this.#transports.add(type, transport, index);
        if (result) {
            this._debug("Registered transport", type);

            if (CometD.#isFunction(transport.registered)) {
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
    unregisterTransport(type) {
        const transport = this.#transports.remove(type);
        if (transport !== null) {
            this._debug("Unregistered transport", type);

            if (CometD.#isFunction(transport.unregistered)) {
                transport.unregistered();
            }
        }
        return transport;
    };

    unregisterTransports() {
        this.#transports.clear();
    };

    /**
     * @return an array of all registered transport types
     */
    getTransportTypes() {
        return this.#transports.getTransportTypes();
    }

    findTransport(name) {
        return this.#transports.find(name);
    }

    /**
     * @returns the TransportRegistry object
     */
    getTransportRegistry() {
        return this.#transports;
    }

    /**
     * Configures the initial Bayeux communication with the Bayeux server.
     * Configuration is passed via an object that must contain a mandatory field <code>url</code>
     * of type string containing the URL of the Bayeux server.
     * @param configuration the configuration object
     */
    configure(configuration) {
        this.#configure(configuration);
    };

    /**
     * Configures and establishes the Bayeux communication with the Bayeux server
     * via a handshake and a subsequent connect.
     * @param configuration the configuration object
     * @param handshakeProps an object to be merged with the handshake message
     */
    init(configuration, handshakeProps) {
        this.configure(configuration);
        this.handshake(handshakeProps);
    };

    /**
     * Establishes the Bayeux communication with the Bayeux server
     * via a handshake and a subsequent connect.
     * @param handshakeProps an object to be merged with the handshake message
     * @param handshakeCallback a function to be invoked when the handshake is acknowledged
     */
    handshake(handshakeProps, handshakeCallback) {
        const status = this.getStatus();
        if (status !== "disconnected") {
            throw "Illegal state: " + status;
        }
        this.#handshake(handshakeProps, handshakeCallback);
    };

    /**
     * Disconnects from the Bayeux server.
     * @param disconnectProps an object to be merged with the disconnect message
     * @param disconnectCallback a function to be invoked when the disconnect is acknowledged
     */
    disconnect(disconnectProps, disconnectCallback) {
        if (this.isDisconnected()) {
            return;
        }

        if (CometD.#isFunction(disconnectProps)) {
            disconnectCallback = disconnectProps;
            disconnectProps = undefined;
        }

        const bayeuxMessage = {
            id: this.#nextMessageId(),
            channel: "/meta/disconnect",
        };
        // Do not allow the user to override important fields.
        const message = this._mixin(false, {}, disconnectProps, bayeuxMessage);

        // Save the callback.
        this._putCallback(message.id, disconnectCallback);

        this.#setStatus("disconnecting");
        this.#send([message], false, "disconnect");
    };

    /**
     * Marks the start of a batch of application messages to be sent to the server
     * in a single request, obtaining a single response containing (possibly) many
     * application reply messages.
     * Messages are held in a queue and not sent until {@link #endBatch()} is called.
     * If startBatch() is called multiple times, then an equal number of endBatch()
     * calls must be made to close and send the batch of messages.
     */
    startBatch() {
        this.#startBatch();
    };

    /**
     * Marks the end of a batch of application messages to be sent to the server
     * in a single request.
     */
    endBatch() {
        this.#endBatch();
    };

    /**
     * Executes the given callback in the given scope, surrounded by a {@link #startBatch()}
     * and {@link #endBatch()} calls.
     * @param scope the scope of the callback, may be omitted
     * @param callback the callback to be executed within {@link #startBatch()} and {@link #endBatch()} calls
     */
    batch(scope, callback) {
        const delegate = this.#resolveScopedCallback(scope, callback);
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
     * The currently supported event is "timeout".
     *
     * The callback function takes an array of messages for which
     * the event happened.
     *
     * For the "timeout" event, the callback function may return a
     * positive value that extends the wait for message replies by
     * the returned amount, in milliseconds.
     *
     * @param {String} event the type of transport event
     * @param {Function} callback the function associate to the given transport event
     */
    addTransportListener(event, callback) {
        if (event !== "timeout") {
            throw "Unsupported event " + event;
        }
        let callbacks = this.#transportListeners[event];
        if (!callbacks) {
            this.#transportListeners[event] = callbacks = [];
        }
        callbacks.push(callback);
    };

    /**
     * Removes the transport listener for the specified transport event.
     * @param {String} event the type of transport event
     * @param {Function} callback the function disassociate from the given transport event
     * @return {boolean} whether the disassociation was successful
     */
    removeTransportListener(event, callback) {
        const callbacks = this.#transportListeners[event];
        if (callbacks) {
            const index = callbacks.indexOf(callback);
            if (index >= 0) {
                callbacks.splice(index, 1);
                return true;
            }
        }
        return false;
    };

    _getTransportListeners(event) {
        return this.#transportListeners[event];
    }

    /**
     * Adds a listener for bayeux messages, performing the given callback in the given scope
     * when a message for the given channel arrives.
     * @param channel the channel the listener is interested to
     * @param scope the scope of the callback, may be omitted
     * @param callback the callback to call when a message is sent to the channel
     * @returns the subscription handle to be passed to {@link #removeListener(object)}
     */
    addListener(channel, scope, callback) {
        if (arguments.length < 2) {
            throw "Illegal arguments number: required 2, got " + arguments.length;
        }
        if (!CometD.#isString(channel)) {
            throw "Illegal argument type: channel must be a string";
        }

        return this.#addListener(channel, scope, callback, true);
    };

    /**
     * Removes the subscription obtained with a call to {@link #addListener(string, object, function)}.
     * @param subscription the subscription to unsubscribe.
     */
    removeListener(subscription) {
        // Beware of subscription.id == 0, which is falsy => cannot use !subscription.id
        if (!subscription || !subscription.channel || !("id" in subscription)) {
            throw "Invalid argument: expected subscription, not " + subscription;
        }

        this.#removeListener(subscription);
    };

    /**
     * Removes all listeners registered with {@link #addListener(channel, scope, callback)} or
     * {@link #subscribe(channel, scope, callback)}.
     */
    clearListeners() {
        this.#listeners = {};
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
    subscribe(channel, scope, callback, subscribeProps, subscribeCallback) {
        if (arguments.length < 2) {
            throw "Illegal arguments number: required 2, got " + arguments.length;
        }
        if (!CometD.#isValidChannel(channel)) {
            throw "Illegal argument: invalid channel " + channel;
        }
        if (this.isDisconnected()) {
            throw "Illegal state: disconnected";
        }

        // Normalize arguments
        if (CometD.#isFunction(scope)) {
            subscribeCallback = subscribeProps;
            subscribeProps = callback;
            callback = scope;
            scope = undefined;
        }
        if (CometD.#isFunction(subscribeProps)) {
            subscribeCallback = subscribeProps;
            subscribeProps = undefined;
        }

        // Only send the message to the server if this client has not yet subscribed to the channel
        const send = !this.#hasSubscriptions(channel);

        const subscription = this.#addListener(channel, scope, callback, false);

        if (send) {
            // Send the subscription message after the subscription registration to avoid
            // races where the server would send a message to the subscribers, but here
            // on the client the subscription has not been added yet to the data structures
            const bayeuxMessage = {
                id: this.#nextMessageId(),
                channel: "/meta/subscribe",
                subscription: channel
            };
            // Do not allow the user to override important fields.
            const message = this._mixin(false, {}, subscribeProps, bayeuxMessage);

            // Save the callback.
            this._putCallback(message.id, subscribeCallback);

            this.#queueSend(message);
        } else {
            if (CometD.#isFunction(subscribeCallback)) {
                // Keep the semantic of calling callbacks asynchronously.
                this.setTimeout(() => {
                    this.#notifyCallback(subscribeCallback, {
                        id: this.#nextMessageId(),
                        successful: true,
                        channel: "/meta/subscribe",
                        subscription: channel
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
    unsubscribe(subscription, unsubscribeProps, unsubscribeCallback) {
        if (arguments.length < 1) {
            throw "Illegal arguments number: required 1, got " + arguments.length;
        }
        if (this.isDisconnected()) {
            throw "Illegal state: disconnected";
        }

        if (CometD.#isFunction(unsubscribeProps)) {
            unsubscribeCallback = unsubscribeProps;
            unsubscribeProps = undefined;
        }

        // Remove the local listener before sending the message
        // This ensures that if the server fails, this client does not get notifications
        this.removeListener(subscription);

        const channel = subscription.channel;
        // Only send the message to the server if this client unsubscribes the last subscription
        if (!this.#hasSubscriptions(channel)) {
            const bayeuxMessage = {
                id: this.#nextMessageId(),
                channel: "/meta/unsubscribe",
                subscription: channel
            };
            // Do not allow the user to override important fields.
            const message = this._mixin(false, {}, unsubscribeProps, bayeuxMessage);

            // Save the callback.
            this._putCallback(message.id, unsubscribeCallback);

            this.#queueSend(message);
        } else {
            if (CometD.#isFunction(unsubscribeCallback)) {
                // Keep the semantic of calling callbacks asynchronously.
                this.setTimeout(() => {
                    this.#notifyCallback(unsubscribeCallback, {
                        id: this.#nextMessageId(),
                        successful: true,
                        channel: "/meta/unsubscribe",
                        subscription: channel
                    });
                }, 0);
            }
        }
    };

    resubscribe(subscription, subscribeProps) {
        this.#removeSubscription(subscription);
        if (subscription) {
            return this.subscribe(subscription.channel, subscription.scope, subscription.callback, subscribeProps);
        }
        return undefined;
    };

    /**
     * Removes all subscriptions added via {@link #subscribe(channel, scope, callback, subscribeProps)},
     * but does not remove the listeners added via {@link addListener(channel, scope, callback)}.
     */
    clearSubscriptions() {
        this.#clearSubscriptions();
    };

    /**
     * Publishes a message on the given channel, containing the given content.
     * @param channel the channel to publish the message to
     * @param content the content of the message
     * @param publishProps an object to be merged with the publish message
     * @param publishCallback a function to be invoked when the publish is acknowledged by the server
     */
    publish(channel, content, publishProps, publishCallback) {
        if (arguments.length < 1) {
            throw "Illegal arguments number: required 1, got " + arguments.length;
        }
        if (!CometD.#isValidChannel(channel)) {
            throw "Illegal argument: invalid channel " + channel;
        }
        if (/^\/meta\//.test(channel)) {
            throw "Illegal argument: cannot publish to meta channels";
        }
        if (this.isDisconnected()) {
            throw "Illegal state: disconnected";
        }

        if (CometD.#isFunction(content)) {
            publishCallback = content;
            content = {};
            publishProps = undefined;
        } else if (CometD.#isFunction(publishProps)) {
            publishCallback = publishProps;
            publishProps = undefined;
        }

        const bayeuxMessage = {
            id: this.#nextMessageId(),
            channel: channel,
            data: content
        };
        // Do not allow the user to override important fields.
        const message = this._mixin(false, {}, publishProps, bayeuxMessage);

        // Save the callback.
        this._putCallback(message.id, publishCallback);

        this.#queueSend(message);
    };

    /**
     * Publishes a message with binary data on the given channel.
     * The binary data chunk may be an ArrayBuffer, a DataView, a TypedArray
     * (such as Uint8Array) or a plain integer array.
     * The metadata object may contain additional application data such as
     * a file name, a mime type, etc.
     * @param channel the channel to publish the message to
     * @param data the binary data to publish
     * @param last whether the binary data chunk is the last
     * @param meta an object containing metadata associated to the binary chunk
     * @param publishProps an object to be merged with the publish message
     * @param publishCallback a function to be invoked when the publish is acknowledged by the server
     */
    publishBinary(channel, data, last, meta, publishProps, publishCallback) {
        if (CometD.#isFunction(data)) {
            publishCallback = data;
            data = new ArrayBuffer(0);
            last = true;
            meta = undefined;
            publishProps = undefined;
        } else if (CometD.#isFunction(last)) {
            publishCallback = last;
            last = true;
            meta = undefined;
            publishProps = undefined;
        } else if (CometD.#isFunction(meta)) {
            publishCallback = meta;
            meta = undefined;
            publishProps = undefined;
        } else if (CometD.#isFunction(publishProps)) {
            publishCallback = publishProps;
            publishProps = undefined;
        }
        const content = {
            meta: meta,
            data: data,
            last: last
        };
        const ext = this._mixin(false, publishProps, {
            ext: {
                binary: {}
            }
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
    remoteCall(target, content, timeout, callProps, callback) {
        if (arguments.length < 1) {
            throw "Illegal arguments number: required 1, got " + arguments.length;
        }
        if (!CometD.#isString(target)) {
            throw "Illegal argument type: target must be a string";
        }
        if (this.isDisconnected()) {
            throw "Illegal state: disconnected";
        }

        if (CometD.#isFunction(content)) {
            callback = content;
            content = {};
            timeout = this.#config.maxNetworkDelay;
            callProps = undefined;
        } else if (CometD.#isFunction(timeout)) {
            callback = timeout;
            timeout = this.#config.maxNetworkDelay;
            callProps = undefined;
        } else if (CometD.#isFunction(callProps)) {
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
        if (!CometD.#isValidChannel(channel)) {
            throw "Illegal argument: invalid target " + target;
        }

        const bayeuxMessage = {
            id: this.#nextMessageId(),
            channel: channel,
            data: content
        };
        const message = this._mixin(false, {}, callProps, bayeuxMessage);

        const context = {
            callback: callback
        };
        if (timeout > 0) {
            context.timeout = this.setTimeout(() => {
                this._debug("Timing out remote call", message, "after", timeout, "ms");
                this.#failMessage({
                    id: message.id,
                    error: "406::timeout",
                    successful: false,
                    failure: {
                        message: message,
                        reason: "Remote Call Timeout",
                    }
                });
            }, timeout);
            this._debug("Scheduled remote call timeout", message, "in", timeout, "ms");
        }
        this.#remoteCalls[message.id] = context;

        this.#queueSend(message);
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
    remoteCallBinary(target, data, last, meta, timeout, callProps, callback) {
        if (CometD.#isFunction(data)) {
            callback = data;
            data = new ArrayBuffer(0);
            last = true;
            meta = undefined;
            timeout = this.#config.maxNetworkDelay;
            callProps = undefined;
        } else if (CometD.#isFunction(last)) {
            callback = last;
            last = true;
            meta = undefined;
            timeout = this.#config.maxNetworkDelay;
            callProps = undefined;
        } else if (CometD.#isFunction(meta)) {
            callback = meta;
            meta = undefined;
            timeout = this.#config.maxNetworkDelay;
            callProps = undefined;
        } else if (CometD.#isFunction(timeout)) {
            callback = timeout;
            timeout = this.#config.maxNetworkDelay;
            callProps = undefined;
        } else if (CometD.#isFunction(callProps)) {
            callback = callProps;
            callProps = undefined;
        }

        const content = {
            meta: meta,
            data: data,
            last: last
        };
        const ext = this._mixin(false, callProps, {
            ext: {
                binary: {}
            }
        });

        this.remoteCall(target, content, timeout, ext, callback);
    };

    /**
     * Returns a string representing the status of the bayeux communication with the Bayeux server.
     */
    getStatus() {
        return this.#status;
    }

    /**
     * Returns whether this instance has been disconnected.
     */
    isDisconnected() {
        return this.#isDisconnected();
    }

    /**
     * Sets the backoff period used to increase the backoff time when retrying an unsuccessful or failed message.
     * Default value is 1 second, which means if there is a persistent failure the retries will happen
     * after 1 second, then after 2 seconds, then after 3 seconds, etc. So for example with 15 seconds of
     * elapsed time, there will be 5 retries (at 1, 3, 6, 10 and 15 seconds elapsed).
     * @param period the backoff period to set
     */
    setBackoffIncrement(period) {
        this.#config.backoffIncrement = period;
    };

    /**
     * Returns the backoff period used to increase the backoff time when retrying an unsuccessful or failed message.
     */
    getBackoffIncrement() {
        return this.#config.backoffIncrement;
    }

    /**
     * Returns the backoff period to wait before retrying an unsuccessful or failed message.
     */
    getBackoffPeriod() {
        return this.#backoff;
    }

    /**
     * Increases the backoff period up to the maximum value configured.
     * @returns the backoff period after increment
     */
    increaseBackoffPeriod() {
        return this.#increaseBackoff();
    }

    /**
     * Resets the backoff period to zero.
     */
    resetBackoffPeriod() {
        this.#resetBackoff();
    };

    /**
     * Sets the log level for console logging.
     * Valid values are the strings "error", "warn", "info" and "debug", from
     * less verbose to more verbose.
     * @param level the log level string
     */
    setLogLevel(level) {
        this.#config.logLevel = level;
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
     */
    registerExtension(name, extension) {
        if (arguments.length < 2) {
            throw "Illegal arguments number: required 2, got " + arguments.length;
        }
        if (!CometD.#isString(name)) {
            throw "Illegal argument type: extension name must be a string";
        }

        let existing = false;
        for (let i = 0; i < this.#extensions.length; ++i) {
            const existingExtension = this.#extensions[i];
            if (existingExtension.name === name) {
                existing = true;
                break;
            }
        }
        if (!existing) {
            this.#extensions.push({
                name: name,
                extension: extension
            });
            this._debug("Registered extension", name);

            // Callback for extensions
            if (CometD.#isFunction(extension.registered)) {
                extension.registered(name, this);
            }

            return true;
        } else {
            this._info("Could not register extension with name", name, "since another extension with the same name already exists");
            return false;
        }
    };

    /**
     * Unregister an extension previously registered with
     * {@link #registerExtension(name, extension)}.
     * @param name the name of the extension to unregister.
     * @return true if the extension was unregistered, false otherwise
     */
    unregisterExtension(name) {
        if (!CometD.#isString(name)) {
            throw "Illegal argument type: extension name must be a string";
        }

        let unregistered = false;
        for (let i = 0; i < this.#extensions.length; ++i) {
            const extension = this.#extensions[i];
            if (extension.name === name) {
                this.#extensions.splice(i, 1);
                unregistered = true;
                this._debug("Unregistered extension", name);

                // Callback for extensions
                const ext = extension.extension;
                if (CometD.#isFunction(ext.unregistered)) {
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
    getExtension(name) {
        for (let i = 0; i < this.#extensions.length; ++i) {
            const extension = this.#extensions[i];
            if (extension.name === name) {
                return extension.extension;
            }
        }
        return null;
    };

    /**
     * Returns the name assigned to this CometD object, or the string "default"
     * if no name has been explicitly passed as parameter to the constructor.
     */
    getName() {
        return this.#name;
    }

    /**
     * Returns the clientId assigned by the Bayeux server during handshake.
     */
    getClientId() {
        return this.#clientId;
    }

    /**
     * Returns the URL of the Bayeux server.
     */
    getURL() {
        const transport = this.getTransport();
        if (transport) {
            let url = transport.url;
            if (url) {
                return url;
            }
            url = this.#config.urls[transport.type];
            if (url) {
                return url;
            }
        }
        return this.#config.url;
    };

    getTransport() {
        return this.#transport;
    }

    getConfiguration() {
        return this._mixin(true, {}, this.#config);
    };

    getAdvice() {
        return this._mixin(true, {}, this.#advice);
    };

    setTimeout(funktion, delay) {
        return this.#scheduler.setTimeout(() => {
            try {
                this._debug("Invoking timed function", funktion);
                funktion();
            } catch (x) {
                this._debug("Exception invoking timed function", funktion, x);
            }
        }, delay);
    }

    clearTimeout(id) {
        this.#scheduler.clearTimeout(id);
    };
}
