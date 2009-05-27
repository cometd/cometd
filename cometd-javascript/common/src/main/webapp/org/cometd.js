/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date$
 */

// Dojo loader support
if (typeof dojo !== 'undefined')
{
    dojo.provide('org.cometd');
}
else
{
    // Namespaces for the cometd implementation
    this.org = this.org || {};
    org.cometd = {};
}

// Abstract APIs
org.cometd.JSON = {};
org.cometd.AJAX = {};
org.cometd.JSON.toJSON = org.cometd.JSON.fromJSON = org.cometd.AJAX.send = function(object)
{
    throw 'Abstract';
};


/**
 * The constructor for a Cometd object, identified by an optional name.
 * The default name is the string 'default'.
 * In the rare case a page needs more than one comet conversation,
 * a new instance can be created via:
 * <pre>
 * var cometUrl2 = ...;
 * var cometd2 = new $.Cometd();
 * cometd2.init({url: cometUrl2});
 * </pre>
 * @param name the optional name of this cometd object
 */
org.cometd.Cometd = function(name)
{
    var _name = name || 'default';
    var _logLevel; // 'warn','info','debug'|'dbug'
    var _url;
    var _maxConnections;
    var _backoffIncrement;
    var _maxBackoff;
    var _reverseIncomingExtensions;
    var _xd = false;
    var _transport;
    var _status = 'disconnected';
    var _messageId = 0;
    var _clientId = null;
    var _batch = 0;
    var _messageQueue = [];
    var _listeners = {};
    var _backoff = 0;
    var _scheduledSend = null;
    var _extensions = [];
    var _advice = {};
    var _handshakeProps;
    var _reestablish = false;

    /**
     * Mixes in the given objects into the target object by copying the properties.
     * @param deep if the copy must be deep
     * @param target the target object
     * @param objects the objects whose properties are copied into the target
     */
    function _mixin(deep, target, objects)
    {
        var result = target || {};

        for (var i = 2; i < arguments.length; ++i)
        {
            var object = arguments[i];

            // Double equal handles both undefined and null
            if (object == null) continue;

            for (name in object)
            {
                var prop = object[name];

                // Avoid infinite loops
                if (prop === target) continue;
                // Do not mixin undefined values
                if (prop === undefined) continue;

                if (deep && typeof prop === "object")
                {
                    result[name] = _mixin(deep, result[name], prop);
                }
                else
                {
                    result[name] = prop;
                }
            }
        }

        return result;
    }

    /**
     * Returns whether the given element is contained into the given array.
     * @param element the element to check presence for
     * @param array the array to check for the element presence
     * @return the index of the element, if present, or a negative index if the element is not present
     */
    function _inArray(element, array)
    {
        for (var i = 0; i < array.length; ++i)
        {
            if (element == array[i]) return i;
        }
        return -1;
    }

    /**
     * Returns the name assigned to this Comet object, or the string 'default'
     * if no name has been explicitely passed as parameter to the constructor.
     */
    this.getName = function()
    {
        return _name;
    };

    /**
     * Configures the initial comet communication with the comet server.
     * Configuration is passed via an object that must contain a mandatory field <code>url</code>
     * of type string containing the URL of the comet server.
     * @param configuration the configuration object
     */
    this.configure = function(configuration)
    {
        _configure(configuration);
    };

    function _configure(configuration)
    {
        _debug('configure cometd ', configuration);
        // Support old style param, where only the comet URL was passed
        if (typeof configuration === 'string') configuration = { url: configuration };
        if (!configuration) configuration = {};

        _url = configuration.url;
        if (!_url) throw 'Missing required configuration parameter \'url\' specifying the comet server URL';
        _maxConnections = configuration.maxConnections || 2;
        _backoffIncrement = configuration.backoffIncrement || 1000;
        _maxBackoff = configuration.maxBackoff || 60000;
        _logLevel = configuration.logLevel || 'info';
        _reverseIncomingExtensions = configuration.reverseIncomingExtensions !== false;


        // Check immediately if we're cross domain
        // If cross domain, the handshake must not send the long polling transport type
        var urlParts = /(^https?:)?(\/\/(([^:\/\?#]+)(:(\d+))?))?([^\?#]*)/.exec(_url);
        if (urlParts[3]) _xd = urlParts[3] != location.host;

        // Temporary setup a transport to send the initial handshake
        // The transport may be changed as a result of handshake
        if (_xd)
            _transport = _newCallbackPollingTransport();
        else
            _transport = _newLongPollingTransport();
        _debug('transport', _transport);
    };

    /**
     * Configures and establishes the comet communication with the comet server
     * via a handshake and a subsequent connect.
     * @param configuration the configuration object
     * @param handshakeProps an object to be merged with the handshake message
     * @see #configure(cometURL)
     * @see #handshake(handshakeProps)
     */
    this.init = function(configuration, handshakeProps)
    {
        _configure(configuration);
        _handshake(handshakeProps);
    };

    /**
     * Establishes the comet communication with the comet server
     * via a handshake and a subsequent connect.
     * @param handshakeProps an object to be merged with the handshake message
     */
    this.handshake = function(handshakeProps)
    {
        _reestablish=false;
        _handshake(handshakeProps);
    };

    /**
     * Disconnects from the comet server.
     * @param disconnectProps an object to be merged with the disconnect message
     */
    this.disconnect = function(disconnectProps)
    {
        if (!_transport)
            return;
        var bayeuxMessage = {
            channel: '/meta/disconnect'
        };
        var message = _mixin({}, disconnectProps, bayeuxMessage);
        _setStatus('disconnecting');
        _send([message], false);
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
    this.startBatch = function()
    {
        _startBatch();
    };

    /**
     * Marks the end of a batch of application messages to be sent to the server
     * in a single request.
     * @see #startBatch()
     */
    this.endBatch = function()
    {
        _endBatch(true);
    };

    /**
     * Subscribes to the given channel, performing the given callback in the given scope
     * when a message for the channel arrives.
     * @param channel the channel to subscribe to
     * @param scope the scope of the callback, may be omitted
     * @param callback the callback to call when a message is sent to the channel
     * @param subscribeProps an object to be merged with the subscribe message
     * @return the subscription handle to be passed to {@link #unsubscribe(object)}
     */
    this.subscribe = function(channel, scope, callback, subscribeProps)
    {
        // Normalize arguments
        if (typeof scope === 'function')
        {
            subscribeProps = callback;
            callback = scope;
            scope = undefined;
        }

        // Only send the message to the server if this clientId has not yet subscribed to the channel
        var send = !_hasSubscriptions(channel);

        var subscription = _addListener(channel, scope, callback, true);

        if (send)
        {
            // Send the subscription message after the subscription registration to avoid
            // races where the server would send a message to the subscribers, but here
            // on the client the subscription has not been added yet to the data structures
            var bayeuxMessage = {
                channel: '/meta/subscribe',
                subscription: channel
            };
            var message = _mixin({}, subscribeProps, bayeuxMessage);
            _queueSend(message);
        }

        return subscription;
    };

    function _hasSubscriptions(channel)
    {
        var subscriptions = _listeners[channel];
        if (subscriptions)
        {
            for (var i = 0; i < subscriptions.length; ++i)
            {
                if (subscriptions[i]) return true;
            }
        }
        return false;
    }

    /**
     * Unsubscribes the subscription obtained with a call to {@link #subscribe(string, object, function)}.
     * @param subscription the subscription to unsubscribe.
     */
    this.unsubscribe = function(subscription, unsubscribeProps)
    {
        // Remove the local listener before sending the message
        // This ensures that if the server fails, this client does not get notifications
        this.removeListener(subscription);

        var channel = subscription[0];
        // Only send the message to the server if this clientId unsubscribes the last subscription
        if (!_hasSubscriptions(channel))
        {
            var bayeuxMessage = {
                channel: '/meta/unsubscribe',
                subscription: channel
            };
            var message = _mixin({}, unsubscribeProps, bayeuxMessage);
            _queueSend(message);
        }
    };

    /**
     * Publishes a message on the given channel, containing the given content.
     * @param channel the channel to publish the message to
     * @param content the content of the message
     * @param publishProps an object to be merged with the publish message
     */
    this.publish = function(channel, content, publishProps)
    {
        var bayeuxMessage = {
            channel: channel,
            data: content
        };
        var message = _mixin({}, publishProps, bayeuxMessage);
        _queueSend(message);
    };

    /**
     * Adds a listener for bayeux messages, performing the given callback in the given scope
     * when a message for the given channel arrives.
     * @param channel the channel the listener is interested to
     * @param scope the scope of the callback, may be omitted
     * @param callback the callback to call when a message is sent to the channel
     * @returns the subscription handle to be passed to {@link #removeListener(object)}
     * @see #removeListener(object)
     */
    this.addListener = function(channel, scope, callback)
    {
        return _addListener(channel, scope, callback, false);
    }

    function _addListener(channel, scope, callback, isSubscription)
    {
        // The data structure is a map<channel, subscription[]>, where each subscription
        // holds the callback to be called and its scope.

        var thiz = scope;
        var method = callback;
        // Normalize arguments
        if (typeof scope === 'function')
        {
            thiz = undefined;
            method = scope;
        }
        else if (typeof callback === 'string')
        {
            if (!scope) throw 'Invalid scope ' + scope;
            method = scope[callback];
            if (!method) throw 'Invalid callback ' + callback + ' for scope ' + scope;
        }

        var subscription = {
            scope: thiz,
            callback: method,
            subscription: isSubscription === true
        };

        var subscriptions = _listeners[channel];
        if (!subscriptions)
        {
            subscriptions = [];
            _listeners[channel] = subscriptions;
        }
        // Pushing onto an array appends at the end and returns the id associated with the element increased by 1.
        // Note that if:
        // a.push('a'); var hb=a.push('b'); delete a[hb-1]; var hc=a.push('c');
        // then:
        // hc==3, a.join()=='a',,'c', a.length==3
        var subscriptionID = subscriptions.push(subscription) - 1;

        _debug('listener', channel, scope, callback, method.name, subscriptionID);

        // The subscription to allow removal of the listener is made of the channel and the index
        return [channel, subscriptionID];
    };

    /**
     * Removes the subscription obtained with a call to {@link #addListener(string, object, function)}.
     * @param subscription the subscription to unsubscribe.
     */
    this.removeListener = function(subscription)
    {
        _removeListener(subscription)
    }

    function _removeListener(subscription)
    {
        var subscriptions = _listeners[subscription[0]];
        if (subscriptions)
        {
            delete subscriptions[subscription[1]];
            _debug('rm listener', subscription);
        }
    };

    /**
     * Removes all listeners registered with {@link #addListener(channel, scope, callback)} or
     * {@link #subscribe(channel, scope, callback)}.
     */
    this.clearListeners = function()
    {
        _listeners = {};
    };

    /**
     * Removes all subscriptions added via {@link #subscribe(channel, scope, callback, subscribeProps)},
     * but does not remove the listeners added via {@link addListener(channel, scope, callback)}.
     */
    this.clearSubscriptions = function()
    {
        _clearSubscriptions();
    }

    function _clearSubscriptions()
    {
        for (var channel in _listeners)
        {
            var subscriptions = _listeners[channel];
            _debug('rm subscriptions', channel, subscriptions);
            for (var i = 0; i < subscriptions.length; ++i)
            {
                var subscription = subscriptions[i];
                if (subscription && subscription.subscription)
                {
                    delete subscriptions[i];
                }
            }
        }
    };

    /**
     * Returns a string representing the status of the bayeux communication with the comet server.
     */
    this.getStatus = function()
    {
        return _status;
    };

    /**
     * Sets the backoff period used to increase the backoff time when retrying an unsuccessful or failed message.
     * Default value is 1 second, which means if there is a persistent failure the retries will happen
     * after 1 second, then after 2 seconds, then after 3 seconds, etc. So for example with 15 seconds of
     * elapsed time, there will be 5 retries (at 1, 3, 6, 10 and 15 seconds elapsed).
     * @param period the backoff period to set
     * @see #getBackoffIncrement()
     */
    this.setBackoffIncrement = function(period)
    {
        _backoffIncrement = period;
    };

    /**
     * Returns the backoff period used to increase the backoff time when retrying an unsuccessful or failed message.
     * @see #setBackoffIncrement(period)
     */
    this.getBackoffIncrement = function()
    {
        return _backoffIncrement;
    };

    /**
     * Returns the backoff period to wait before retrying an unsuccessful or failed message.
     */
    this.getBackoffPeriod = function()
    {
        return _backoff;
    };

    /**
     * Sets the log level for console logging.
     * Valid values are the strings 'error', 'warn', 'info' and 'debug', from
     * less verbose to more verbose.
     * @param level the log level string
     */
    this.setLogLevel = function(level)
    {
        _logLevel = level;
    };

    /**
     * Registers an extension whose callbacks are called for every incoming message
     * (that comes from the server to this client implementation) and for every
     * outgoing message (that originates from this client implementation for the
     * server).
     * The format of the extension object is the following:
     * <pre>
     * {
     *     incoming: function(message) { ... },
     *     outgoing: function(message) { ... }
     * }
     * </pre>
     * Both properties are optional, but if they are present they will be called
     * respectively for each incoming message and for each outgoing message.
     * @param name the name of the extension
     * @param extension the extension to register
     * @return true if the extension was registered, false otherwise
     * @see #unregisterExtension(name)
     */
    this.registerExtension = function(name, extension)
    {
        var existing = false;
        for (var i = 0; i < _extensions.length; ++i)
        {
            var existingExtension = _extensions[i];
            if (existingExtension.name == name)
            {
                existing = true;
                break;
            }
        }
        if (!existing)
        {
            _extensions.push({
                name: name,
                extension: extension
            });
            _debug('Registered extension', name);

            // Callback for extensions
            if (typeof extension.registered === 'function') extension.registered.call(extension, name, this);

            return true;
        }
        else
        {
            _info('Could not register extension with name \'{}\': another extension with the same name already exists');
            return false;
        }
    };

    /**
     * Unregister an extension previously registered with
     * {@link #registerExtension(name, extension)}.
     * @param name the name of the extension to unregister.
     * @return true if the extension was unregistered, false otherwise
     */
    this.unregisterExtension = function(name)
    {
        var unregistered = false;
        for (var i = 0; i < _extensions.length; ++i)
        {
            var extension = _extensions[i];
            if (extension.name == name)
            {
                _extensions.splice(i, 1);
                unregistered = true;
                _debug('Unregistered extension', name);

                // Callback for extensions
                if (typeof extension.unregistered === 'function') extension.unregistered.call(extension);

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
    this.getExtension = function(name)
    {
        for (var i = 0; i < _extensions.length; ++i)
        {
            var extension = _extensions[i];
            if (extension.name == name) return extension.extension;
        }
        return null;
    };

    /**
     * Starts a the batch of messages to be sent in a single request.
     * @see _endBatch(sendMessages)
     */
    function _startBatch()
    {
        ++_batch;
    };

    /**
     * Ends the batch of messages to be sent in a single request,
     * optionally sending messages present in the message queue depending
     * on the given argument.
     * @param sendMessages whether to send the messages in the queue or not
     * @see _startBatch()
     */
    function _endBatch(sendMessages)
    {
        --_batch;
        if (_batch < 0) _batch = 0;
        if (sendMessages && _batch == 0 && !_isDisconnected())
        {
            var messages = _messageQueue;
            _messageQueue = [];
            if (messages.length > 0) _send(messages, false);
        }
    };

    function _nextMessageId()
    {
        return ++_messageId;
    };

    /**
     * Converts the given response into an array of bayeux messages
     * @param response the response to convert
     * @return an array of bayeux messages obtained by converting the response
     */
    function _convertToMessages(response)
    {
        if (response === undefined) return [];
        if (response instanceof Array) return response;
        if (response instanceof String || typeof response == 'string') return org.cometd.JSON.fromJSON(response);
        if (response instanceof Object) return [response];
        throw 'Conversion Error ' + response + ', typeof ' + (typeof response);
    };

    function _setStatus(newStatus)
    {
        _debug('status',_status,'->',newStatus);
        _status = newStatus;
    };

    function _isDisconnected()
    {
        return _status == 'disconnecting' || _status == 'disconnected';
    };

    /**
     * Sends the initial handshake message
     */
    function _handshake(handshakeProps)
    {
        _debug('handshake');
        _clientId = null;

        _clearSubscriptions();

        // Start a batch.
        // This is needed because handshake and connect are async.
        // It may happen that the application calls init() then subscribe()
        // and the subscribe message is sent before the connect message, if
        // the subscribe message is not held until the connect message is sent.
        // So here we start a batch to hold temporarly any message until
        // the connection is fully established.
        _batch = 0;
        _startBatch();

        // Save the original properties provided by the user
        // Deep copy to avoid the user to be able to change them later
        _handshakeProps = _mixin(true, {}, handshakeProps);

        var bayeuxMessage = {
            version: '1.0',
            minimumVersion: '0.9',
            channel: '/meta/handshake',
            supportedConnectionTypes: _xd ? ['callback-polling'] : ['long-polling', 'callback-polling']
        };
        // Do not allow the user to mess with the required properties,
        // so merge first the user properties and *then* the bayeux message
        var message = _mixin({}, handshakeProps, bayeuxMessage);

        // We started a batch to hold the application messages,
        // so here we must bypass it and send immediately.
        _setStatus('handshaking');
        _debug('handshake send',message);
        _send([message], false);
    };

    function _findTransport(handshakeResponse)
    {
        var transportTypes = handshakeResponse.supportedConnectionTypes;
        if (_xd)
        {
            // If we are cross domain, check if the server supports it, that's the only option
            if (_inArray('callback-polling', transportTypes) >= 0) return _transport;
        }
        else
        {
            // Check if we can keep long-polling
            if (_inArray('long-polling', transportTypes) >= 0) return _transport;

            // The server does not support long-polling
            if (_inArray('callback-polling', transportTypes) >= 0) return _newCallbackPollingTransport();
        }
        return null;
    };

    function _delayedHandshake()
    {
        _setStatus('handshaking');
        _delayedSend(function()
        {
            _handshake(_handshakeProps);
        });
    };

    function _delayedConnect()
    {
        _setStatus('connecting');
        _delayedSend(function()
        {
            _connect();
        });
    };

    function _delayedSend(operation)
    {
        _cancelDelayedSend();
        var delay = _backoff;
        if (_advice.interval && _advice.interval > 0)
            delay += _advice.interval;
        _scheduledSend = _setTimeout(operation, delay);
    };

    function _cancelDelayedSend()
    {
        if (_scheduledSend !== null) clearTimeout(_scheduledSend);
        _scheduledSend = null;
    };

    function _setTimeout(funktion, delay)
    {
        return setTimeout(function()
        {
            try
            {
                funktion();
            }
            catch (x)
            {
                _debug(funktion.name, x);
            }
        }, delay);
    };

    /**
     * Sends the connect message
     */
    function _connect()
    {
        _debug('connect');
        var message = {
            channel: '/meta/connect',
            connectionType: _transport.getType()
        };
        _setStatus('connecting');
        _send([message], true);
        _setStatus('connected');
    };

    function _queueSend(message)
    {
        if (_batch > 0)
            _messageQueue.push(message);
        else
            _send([message], false);
    };

    /**
     * Delivers the messages to the comet server
     * @param messages the array of messages to send
     * @param longpoll true if this send is a long poll
     */
    function _send(messages, longpoll)
    {
        // We must be sure that the messages have a clientId.
        // This is not guaranteed since the handshake may take time to return
        // (and hence the clientId is not known yet) and the application
        // may create other messages.
        for (var i = 0; i < messages.length; ++i)
        {
            var message = messages[i];
            message['id'] = _nextMessageId();
            if (_clientId)
                message['clientId'] = _clientId;
            message = _applyOutgoingExtensions(message);
            if (message!=null)
                messages[i] = message;
            else
                messages.splice(i--, 1);
        }
        if (messages.length==0)
            return;

        var self = this;
        var envelope = {
            url: _url,
            messages: messages,
            onSuccess: function(request, response)
            {
                try
                {
                    _handleResponse.call(self, request, response, longpoll);
                }
                catch (x)
                {
                    _debug(x);
                }
            },
            onFailure: function(request, reason, exception)
            {
                try
                {
                    _handleFailure.call(self, request, messages, reason, exception, longpoll);
                }
                catch (x)
                {
                    _debug(x);
                }
            }
        };
        _debug('send', envelope);
        _transport.send(envelope, longpoll);
    };

    function _applyIncomingExtensions(message)
    {
        for (var i = 0; message!=null && i<_extensions.length; ++i)
        {
            var index = _reverseIncomingExtensions ? _extensions.length - 1 - i : i;
            var extension = _extensions[index];
            var callback = extension.extension.incoming;
            if (callback && typeof callback === 'function')
            {
                message = _applyExtension(extension.name, callback, message);
            }
        }
        return message;
    };

    function _applyOutgoingExtensions(message)
    {
        for (var i = 0; message!=null && i<_extensions.length; ++i)
        {
            var extension = _extensions[i];
            var callback = extension.extension.outgoing;
            if (callback && typeof callback == 'function')
            {
                message = _applyExtension(extension.name, callback, message);
            }
        }
        return message;
    };

    function _applyExtension(name, callback, message)
    {
        try
        {
            return callback(message);
        }
        catch (x)
        {
            _debug(x);
            return message;
        }
    };

    function _receive(message)
    {
        if (message.advice) _advice = message.advice;

        var channel = message.channel;
        switch (channel)
        {
            case '/meta/handshake':
                _handshakeResponse(message);
                break;
            case '/meta/connect':
                _connectResponse(message);
                break;
            case '/meta/disconnect':
                _disconnectResponse(message);
                break;
            case '/meta/subscribe':
                _subscribeResponse(message);
                break;
            case '/meta/unsubscribe':
                _unsubscribeResponse(message);
                break;
            default:
                _messageResponse(message);
                break;
        }
    };

    /**
     * Receive a message.
     * This method is exposed as a public message so extensions may inject
     * messages as if they had been received.
     */
    this.receive = _receive;

    function _handleResponse(request, response, longpoll)
    {
        var messages = _convertToMessages(response);
        _debug('Received', messages);

        // Signal the transport it can send other queued requests
        _transport.complete(request, true, longpoll);

        for (var i = 0; i < messages.length; ++i)
        {
            var message = messages[i];
            message = _applyIncomingExtensions(message);
            if (message==null)
                continue;

            _receive(message);
        }
    }

    function _handleFailure(request, messages, reason, exception, longpoll)
    {
        var xhr = request.xhr;

        _debug('Failed', messages);

        // Signal the transport it can send other queued requests
        _transport.complete(request, false, longpoll);

        for (var i = 0; i < messages.length; ++i)
        {
            var message = messages[i];
            var channel = message.channel;
            switch (channel)
            {
                case '/meta/handshake':
                    _handshakeFailure(xhr, message);
                    break;
                case '/meta/connect':
                    _connectFailure(xhr, message);
                    break;
                case '/meta/disconnect':
                    _disconnectFailure(xhr, message);
                    break;
                case '/meta/subscribe':
                    _subscribeFailure(xhr, message);
                    break;
                case '/meta/unsubscribe':
                    _unsubscribeFailure(xhr, message);
                    break;
                default:
                    _messageFailure(xhr, message);
                    break;
            }
        }
    };

    function _handshakeResponse(message)
    {
        if (message.successful)
        {
            // Save clientId, figure out transport, then follow the advice to connect
            _clientId = message.clientId;

            var newTransport = _findTransport(message);
            if (newTransport === null)
            {
                throw 'Could not agree on transport with server';
            }
            else
            {
                if (_transport.getType() != newTransport.getType())
                {
                    _debug('transport', _transport, '->',newTransport);
                    _transport = newTransport;
                }
            }

            // Notify the listeners
            // Here the new transport is in place, as well as the clientId, so
            // the listener can perform a publish() if it wants, and the listeners
            // are notified before the connect below.
            message.reestablish = _reestablish;
            _reestablish = true;
            _notifyListeners('/meta/handshake', message);

            var action = _advice.reconnect ? _advice.reconnect : 'retry';
            switch (action)
            {
                case 'retry':
                    _delayedConnect();
                    break;
                default:
                    break;
            }
        }
        else
        {
            var retry = !_isDisconnected() && _advice.reconnect != 'none';
            if (!retry) _setStatus('disconnected');

            _notifyListeners('/meta/handshake', message);
            _notifyListeners('/meta/unsuccessful', message);

            // Only try again if we haven't been disconnected and
            // the advice permits us to retry the handshake
            if (retry)
            {
                _increaseBackoff();
                _delayedHandshake();
            }
        }
    };

    function _handshakeFailure(xhr, message)
    {
        // Notify listeners
        var failureMessage = {
            successful: false,
            failure: true,
            channel: '/meta/handshake',
            request: message,
            xhr: xhr,
            advice: {
                action: 'retry',
                interval: _backoff
            }
        };

        var retry = !_isDisconnected() && _advice.reconnect != 'none';
        if (!retry) _setStatus('disconnected');

        _notifyListeners('/meta/handshake', failureMessage);
        _notifyListeners('/meta/unsuccessful', failureMessage);

        // Only try again if we haven't been disconnected and the
        // advice permits us to try again
        if (retry)
        {
            _increaseBackoff();
            _delayedHandshake();
        }
    };

    function _connectResponse(message)
    {
        var action = _isDisconnected() ? 'none' : (_advice.reconnect ? _advice.reconnect : 'retry');
        if (!_isDisconnected()) _setStatus(action == 'retry' ? 'connecting' : 'disconnecting');

        if (message.successful)
        {
            // End the batch and allow held messages from the application
            // to go to the server (see _handshake() where we start the batch).
            // The batch is ended before notifying the listeners, so that
            // listeners can batch other cometd operations
            _endBatch(true);

            // Notify the listeners after the status change but before the next connect
            _notifyListeners('/meta/connect', message);

            // Connect was successful.
            // Normally, the advice will say "reconnect: 'retry', interval: 0"
            // and the server will hold the request, so when a response returns
            // we immediately call the server again (long polling)
            switch (action)
            {
                case 'retry':
                    _resetBackoff();
                    _delayedConnect();
                    break;
                default:
                    _resetBackoff();
                    _setStatus('disconnected');
                    break;
            }
        }
        else
        {
            // Notify the listeners after the status change but before the next action
            _notifyListeners('/meta/connect', message);
            _notifyListeners('/meta/unsuccessful', message);

            // Connect was not successful.
            // This may happen when the server crashed, the current clientId
            // will be invalid, and the server will ask to handshake again
            switch (action)
            {
                case 'retry':
                    _increaseBackoff();
                    _delayedConnect();
                    break;
                case 'handshake':
                    // End the batch but do not send the messages until we connect successfully
                    _endBatch(false);
                    _resetBackoff();
                    _delayedHandshake();
                    break;
                case 'none':
                    _resetBackoff();
                    _setStatus('disconnected');
                    break;
            }
        }
    };

    function _connectFailure(xhr, message)
    {
        // Notify listeners
        var failureMessage = {
            successful: false,
            failure: true,
            channel: '/meta/connect',
            request: message,
            xhr: xhr,
            advice: {
                action: 'retry',
                interval: _backoff
            }
        };
        _notifyListeners('/meta/connect', failureMessage);
        _notifyListeners('/meta/unsuccessful', failureMessage);

        if (!_isDisconnected())
        {
            var action = _advice.reconnect ? _advice.reconnect : 'retry';
            switch (action)
            {
                case 'retry':
                    _increaseBackoff();
                    _delayedConnect();
                    break;
                case 'handshake':
                    _resetBackoff();
                    _delayedHandshake();
                    break;
                case 'none':
                    _resetBackoff();
                    break;
                default:
                    _debug('Unrecognized action', action);
                    break;
            }
        }
    };

    function _disconnectResponse(message)
    {
        if (message.successful)
        {
            _disconnect(false);
            _notifyListeners('/meta/disconnect', message);
        }
        else
        {
            _disconnect(true);
            _notifyListeners('/meta/disconnect', message);
            _notifyListeners('/meta/usuccessful', message);
        }
    };

    function _disconnect(abort)
    {
        _cancelDelayedSend();
        if (abort) _transport.abort();
        _clientId = null;
        _setStatus('disconnected');
        _batch = 0;
        _messageQueue = [];
        _resetBackoff();
    };

    function _disconnectFailure(xhr, message)
    {
        _disconnect(true);

        var failureMessage = {
            successful: false,
            failure: true,
            channel: '/meta/disconnect',
            request: message,
            xhr: xhr,
            advice: {
                action: 'none',
                interval: 0
            }
        };
        _notifyListeners('/meta/disconnect', failureMessage);
        _notifyListeners('/meta/unsuccessful', failureMessage);
    };

    function _subscribeResponse(message)
    {
        if (message.successful)
        {
            _notifyListeners('/meta/subscribe', message);
        }
        else
        {
            _notifyListeners('/meta/subscribe', message);
            _notifyListeners('/meta/unsuccessful', message);
        }
    };

    function _subscribeFailure(xhr, message)
    {
        var failureMessage = {
            successful: false,
            failure: true,
            channel: '/meta/subscribe',
            request: message,
            xhr: xhr,
            advice: {
                action: 'none',
                interval: 0
            }
        };
        _notifyListeners('/meta/subscribe', failureMessage);
        _notifyListeners('/meta/unsuccessful', failureMessage);
    };

    function _unsubscribeResponse(message)
    {
        if (message.successful)
        {
            _notifyListeners('/meta/unsubscribe', message);
        }
        else
        {
            _notifyListeners('/meta/unsubscribe', message);
            _notifyListeners('/meta/unsuccessful', message);
        }
    };

    function _unsubscribeFailure(xhr, message)
    {
        var failureMessage = {
            successful: false,
            failure: true,
            channel: '/meta/unsubscribe',
            request: message,
            xhr: xhr,
            advice: {
                action: 'none',
                interval: 0
            }
        };
        _notifyListeners('/meta/unsubscribe', failureMessage);
        _notifyListeners('/meta/unsuccessful', failureMessage);
    };

    function _messageResponse(message)
    {
        if (message.successful === undefined)
        {
            if (message.data)
            {
                // It is a plain message, and not a bayeux meta message
                _notifyListeners(message.channel, message);
            }
            else
            {
                _debug('Unknown message', message);
            }
        }
        else
        {
            if (message.successful)
            {
                _notifyListeners('/meta/publish', message);
            }
            else
            {
                _notifyListeners('/meta/publish', message);
                _notifyListeners('/meta/unsuccessful', message);
            }
        }
    };

    function _messageFailure(xhr, message)
    {
        var failureMessage = {
            successful: false,
            failure: true,
            channel: message.channel,
            request: message,
            xhr: xhr,
            advice: {
                action: 'none',
                interval: 0
            }
        };
        _notifyListeners('/meta/publish', failureMessage);
        _notifyListeners('/meta/unsuccessful', failureMessage);
    };

    function _notifyListeners(channel, message)
    {
        // Notify direct listeners
        _notify(channel, message);

        // Notify the globbing listeners
        var channelParts = channel.split("/");
        var last = channelParts.length - 1;
        for (var i = last; i > 0; --i)
        {
            var channelPart = channelParts.slice(0, i).join('/') + '/*';
            // We don't want to notify /foo/* if the channel is /foo/bar/baz,
            // so we stop at the first non recursive globbing
            if (i == last) _notify(channelPart, message);
            // Add the recursive globber and notify
            channelPart += '*';
            _notify(channelPart, message);
        }
    };

    function _notify(channel, message)
    {
        var subscriptions = _listeners[channel];
        if (subscriptions && subscriptions.length > 0)
        {
            for (var i = 0; i < subscriptions.length; ++i)
            {
                var subscription = subscriptions[i];
                // Subscriptions may come and go, so the array may have 'holes'
                if (subscription)
                {
                    try
                    {
                        subscription.callback.call(subscription.scope, message);
                    }
                    catch (x)
                    {
                        _warn(subscription,message,x);
                    }
                }
            }
        }
    };

    function _resetBackoff()
    {
        _backoff = 0;
    };

    function _increaseBackoff()
    {
        if (_backoff < _maxBackoff)
            _backoff += _backoffIncrement;
    };

    var _warn = this._warn = function()
    {
        _log('WARN', arguments);
    };

    var _info = this._info = function()
    {
        if (_logLevel != 'warn')
            _log('INFO',arguments);
    };

    var _debug = this._debug = function()
    {
        if (_logLevel=='debug' || _logLevel=='dbug')
            _log('DBUG',arguments);
    };

    function _log(level, args)
    {
        if (window.console)
        {
            var a = new Array();
            a[0] = level;
            for (var i = 0; i < args.length; i++)
                a[i + 1] = args[i];
            window.console.log.apply(window.console, a);
        }
    };

    function _newLongPollingTransport()
    {
        return _mixin({}, new org.cometd.Transport('long-polling'), new org.cometd.LongPollingTransport());
    };

    function _newCallbackPollingTransport()
    {
        return _mixin({}, new org.cometd.Transport('callback-polling'), new org.cometd.CallbackPollingTransport());
    };

    /**
     * Base object with the common functionality for transports.
     * The key responsibility is to allow at most 2 outstanding requests to the server,
     * to avoid that requests are sent behind a long poll.
     * To achieve this, we have one reserved request for the long poll, and all other
     * requests are serialized one after the other.
     */
    org.cometd.Transport = function(type)
    {
        var _requestIds = 0;
        var _longpollRequest = null;
        var _requests = [];
        var _envelopes = [];

        this.getType = function()
        {
            return type;
        };

        this.send = function(envelope, longpoll)
        {
            if (longpoll)
                _longpollSend(this, envelope);
            else
                _queueSend(this, envelope);
        };

        function _longpollSend(self, envelope)
        {
            if (_longpollRequest !== null)
                throw 'Concurrent longpoll requests not allowed, request ' + _longpollRequest.id + ' not yet completed';

            var requestId = ++_requestIds;
            var request = {id: requestId};
            self._send(envelope, request);
            _longpollRequest = request;
        };

        function _queueSend(self, envelope)
        {
            var requestId = ++_requestIds;

            var request = {id: requestId};
            // Consider the longpoll requests which should always be present
            if (_requests.length < _maxConnections - 1)
            {
                self._send(envelope, request);
                _requests.push(request);
            }
            else
            {
                _envelopes.push([envelope, request]);
            }
        };

        this.complete = function(request, success, longpoll)
        {
            if (longpoll)
                _longpollComplete(request);
            else
                _complete(this, request, success);
        };

        function _longpollComplete(request)
        {
            var requestId = request.id;
            if (_longpollRequest !== request) throw 'Comet request mismatch, completing request ' + requestId;

            // Reset longpoll request
            _longpollRequest = null;
        };

        function _complete(self, request, success)
        {
            var index = _inArray(request, _requests);
            // The index can be negative the request has been aborted
            if (index >= 0) _requests.splice(index, 1);

            if (_envelopes.length > 0)
            {
                var envelope = _envelopes.shift();
                if (success)
                {
                    _queueSend(self, envelope[0]);
                }
                else
                {
                    // Keep the semantic of calling response callbacks asynchronously after the request
                    setTimeout(function() { envelope[0].onFailure(envelope[1], 'error'); }, 0);
                }
            }
        };

        this.abort = function()
        {
            for (var i = 0; i < _requests.length; ++i)
            {
                var request = _requests[i];
                _debug('Aborting request', request);
                if (request.xhr)
                    request.xhr.abort();
            }
            if (_longpollRequest)
            {
                _debug('Aborting request ', _longpollRequest);
                if (_longpollRequest.xhr) _longpollRequest.xhr.abort();
            }
            _longpollRequest = null;
            _requests = [];
            _envelopes = [];
        };
    };

    org.cometd.LongPollingTransport = function()
    {
        this._send = function(envelope, request)
        {
            request.xhr = org.cometd.AJAX.send({
                transport: this,
                url: envelope.url,
                headers: {
                    Connection: 'Keep-Alive'
                },
                body: org.cometd.JSON.toJSON(envelope.messages),
                onSuccess: function(response) { envelope.onSuccess(request, response); },
                onError: function(reason, exception) { envelope.onFailure(request, reason, exception); }
            });
        };
    };

    org.cometd.CallbackPollingTransport = function()
    {
        var _maxLength = 2000;
        this._send = function(envelope, request)
        {
            // Microsoft Internet Explorer has a 2083 URL max length
            // We must ensure that we stay within that length
            var messages = org.cometd.JSON.toJSON(envelope.messages);
            // Encode the messages because all brackets, quotes, commas, colons, etc
            // present in the JSON will be URL encoded, taking many more characters
            var urlLength = envelope.url.length + encodeURI(messages).length;

            // Let's stay on the safe side and use 2000 instead of 2083
            // also because we did not count few characters among which
            // the parameter name 'message' and the parameter 'jsonp',
            // which sum up to about 50 chars
            if (urlLength > _maxLength)
            {
                var x = envelope.messages.length > 1 ?
                        'Too many bayeux messages in the same batch resulting in message too big ' +
                        '(' + urlLength + ' bytes, max is ' + _maxLength + ') for transport ' + this.getType() :
                        'Bayeux message too big (' + urlLength + ' bytes, max is ' + _maxLength + ') ' +
                        'for transport ' + this.getType();
                // Keep the semantic of calling response callbacks asynchronously after the request
                _setTimeout(function() { envelope.onFailure(request, 'error', x); }, 0);
            }
            else
            {
                org.cometd.AJAX.send({
                    transport: this,
                    url: envelope.url,
                    headers: {
                        Connection: 'Keep-Alive'
                    },
                    body: messages,
                    onSuccess: function(response) { envelope.onSuccess(request, response); },
                    onError: function(reason, exception) { envelope.onFailure(request, reason, exception); }
                });
            }
        };
    };
};
