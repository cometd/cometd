/**
 * Copyright 2008 Mort Bay Consulting Pty. Ltd.
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * ----------------------------------------------------------------------------
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http: *www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ----------------------------------------------------------------------------
 * Licensed under the MIT license;
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * ----------------------------------------------------------------------------
 * $Revision$ $Date$
 */
(function($)
{
    /**
     * The constructor for a Comet object.
     * There is a default Comet instance already created at the variable <code>$.cometd</code>,
     * and hence that can be used to start a comet conversation with a server.
     * In the rare case a page needs more than one comet conversation, a new instance can be
     * created via:
     * <pre>
     * var url2 = ...;
     * var cometd2 = new $.Cometd();
     * cometd2.init(url2);
     * </pre>
     */
    $.Cometd = function(name)
    {
        var _name = name || 'default';
        var _logPriorities = { debug: 1, info: 2, warn: 3, error: 4 };
        var _logLevel = 'info';
        var _url;
        var _xd = false;
        var _transport;
        var _status = 'disconnected';
        var _messageId = 0;
        var _clientId = null;
        var _batch = 0;
        var _messageQueue = [];
        var _listeners = {};
        var _backoff = 0;
        var _backoffIncrement = 1000;
        var _maxBackoff = 60000;
        var _scheduledSend = null;
        var _extensions = [];
        var _advice = {};
        var _handshakeProps;

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
         * @param cometURL the URL of the comet server
         */
        this.configure = function(cometURL)
        {
            _configure(cometURL);
        };

        function _configure(cometURL)
        {
            _url = cometURL;
            _debug('Initializing comet with url: {}', _url);

            // Check immediately if we're cross domain
            // If cross domain, the handshake must not send the long polling transport type
            var urlParts = /(^https?:)?(\/\/(([^:\/\?#]+)(:(\d+))?))?([^\?#]*)/.exec(cometURL);
            if (urlParts[3]) _xd = urlParts[3] != location.host;

            // Temporary setup a transport to send the initial handshake
            // The transport may be changed as a result of handshake
            if (_xd)
                _transport = newCallbackPollingTransport();
            else
                _transport = newLongPollingTransport();
            _debug('Initial transport is {}', _transport.getType());
        };

        /**
         * Configures and establishes the comet communication with the comet server
         * via a handshake and a subsequent connect.
         * @param cometURL the URL of the comet server
         * @param handshakeProps an object to be merged with the handshake message
         * @see #configure(cometURL)
         * @see #handshake(handshakeProps)
         */
        this.init = function(cometURL, handshakeProps)
        {
            _configure(cometURL);
            _handshake(handshakeProps);
        };

        /**
         * Establishes the comet communication with the comet server
         * via a handshake and a subsequent connect.
         * @param handshakeProps an object to be merged with the handshake message
         */
        this.handshake = function(handshakeProps)
        {
            _handshake(handshakeProps);
        };

        /**
         * Disconnects from the comet server.
         * @param disconnectProps an object to be merged with the disconnect message
         */
        this.disconnect = function(disconnectProps)
        {
            var bayeuxMessage = {
                channel: '/meta/disconnect'
            };
            var message = $.extend({}, disconnectProps, bayeuxMessage);
            // Deliver immediately
            // The handshake and connect mechanism make use of startBatch(), and in case
            // of a failed handshake the disconnect would not be delivered if using _send().
            _setStatus('disconnecting');
            _deliver([message], false);
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
         * @param scope the scope of the callback
         * @param callback the callback to call when a message is delivered to the channel
         * @param subscribeProps an object to be merged with the subscribe message
         * @return the subscription handle to be passed to {@link #unsubscribe(object)}
         */
        this.subscribe = function(channel, scope, callback, subscribeProps)
        {
            var subscription = this.addListener(channel, scope, callback);

            // Send the subscription message after the subscription registration to avoid
            // races where the server would deliver a message to the subscribers, but here
            // on the client the subscription has not been added yet to the data structures
            var bayeuxMessage = {
                channel: '/meta/subscribe',
                subscription: channel
            };
            var message = $.extend({}, subscribeProps, bayeuxMessage);
            _send(message);

            return subscription;
        };

        /**
         * Unsubscribes the subscription obtained with a call to {@link #subscribe(string, object, function)}.
         * @param subscription the subscription to unsubscribe.
         */
        this.unsubscribe = function(subscription, unsubscribeProps)
        {
            // Remove the local listener before sending the message
            // This ensures that if the server fails, this client does not get notifications
            this.removeListener(subscription);
            var bayeuxMessage = {
                channel: '/meta/unsubscribe',
                subscription: subscription[0]
            };
            var message = $.extend({}, unsubscribeProps, bayeuxMessage);
            _send(message);
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
            var message = $.extend({}, publishProps, bayeuxMessage);
            _send(message);
        };

        /**
         * Adds a listener for bayeux messages, performing the given callback in the given scope
         * when a message for the given channel arrives.
         * @param channel the channel the listener is interested to
         * @param scope the scope of the callback
         * @param callback the callback to call when a message is delivered to the channel
         * @returns the subscription handle to be passed to {@link #removeListener(object)}
         * @see #removeListener(object)
         */
        this.addListener = function(channel, scope, callback)
        {
            // The data structure is a map<channel, subscription[]>, where each subscription
            // holds the callback to be called and its scope.

            // Normalize arguments
            if (!callback)
            {
                callback = scope;
                scope = undefined;
            }

            var subscription = {
                scope: scope,
                callback: callback
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
            var subscriptionIndex = subscriptions.push(subscription) - 1;
            _debug('Added listener: channel \'{}\', callback \'{}\', index {}', channel, callback.name, subscriptionIndex);

            // The subscription to allow removal of the listener is made of the channel and the index
            return [channel, subscriptionIndex];
        };

        /**
         * Removes the subscription obtained with a call to {@link #addListener(string, object, function)}.
         * @param subscription the subscription to unsubscribe.
         */
        this.removeListener = function(subscription)
        {
            var subscriptions = _listeners[subscription[0]];
            if (subscriptions)
            {
                delete subscriptions[subscription[1]];
                _debug('Removed listener: channel \'{}\', index {}', subscription[0], subscription[1]);
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
         * Both properties are optional, but if they are present they will be called
         * respectively for each incoming message and for each outgoing message.
         * </pre>
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
                    return false;
                }
            }
            if (!existing)
            {
                _extensions.push({
                    name: name,
                    extension: extension
                });
                _debug('Registered extension \'{}\'', name);
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
            $.each(_extensions, function(index, extension)
            {
                if (extension.name == name)
                {
                    _extensions.splice(index, 1);
                    unregistered = true;
                    _debug('Unregistered extension \'{}\'', name);
                    return false;
                }
            });
            return unregistered;
        };

        /**
         * Starts a the batch of messages to be sent in a single request.
         * @see _endBatch(deliverMessages)
         */
        function _startBatch()
        {
            ++_batch;
        };

        /**
         * Ends the batch of messages to be sent in a single request,
         * optionally delivering messages present in the message queue depending
         * on the given argument.
         * @param deliverMessages whether to deliver the messages in the queue or not
         * @see _startBatch()
         */
        function _endBatch(deliverMessages)
        {
            --_batch;
            if (_batch < 0) _batch = 0;
            if (deliverMessages && _batch == 0 && !_isDisconnected())
            {
                var messages = _messageQueue;
                _messageQueue = [];
                if (messages.length > 0) _deliver(messages, false);
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
            if (response instanceof String || typeof response == 'string') return eval('(' + response + ')');
            if (response instanceof Object) return [response];
            throw 'Conversion Error ' + response + ', typeof ' + (typeof response);
        };

        function _setStatus(newStatus)
        {
            _debug('{} -> {}', _status, newStatus);
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
            _debug('Starting handshake');
            _clientId = null;

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
            _handshakeProps = $.extend(true, {}, handshakeProps);

            var bayeuxMessage = {
                version: '1.0',
                minimumVersion: '0.9',
                channel: '/meta/handshake',
                supportedConnectionTypes: _xd ? ['callback-polling'] : ['long-polling', 'callback-polling']
            };
            // Do not allow the user to mess with the required properties,
            // so merge first the user properties and *then* the bayeux message
            var message = $.extend({}, handshakeProps, bayeuxMessage);

            // We started a batch to hold the application messages,
            // so here we must bypass it and deliver immediately.
            _setStatus('handshaking');
            _deliver([message], false);
        };

        function _findTransport(handshakeResponse)
        {
            var transportTypes = handshakeResponse.supportedConnectionTypes;
            if (_xd)
            {
                // If we are cross domain, check if the server supports it, that's the only option
                if ($.inArray('callback-polling', transportTypes) >= 0) return _transport;
            }
            else
            {
                // Check if we can keep long-polling
                if ($.inArray('long-polling', transportTypes) >= 0) return _transport;

                // The server does not support long-polling
                if ($.inArray('callback-polling', transportTypes) >= 0) return newCallbackPollingTransport();
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
            _debug("Delayed send: backoff {}, interval {}", _backoff, _advice.interval);
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
                    _debug('Exception during scheduled execution of function \'{}\': {}', funktion.name, x);
                }
            }, delay);
        };

        /**
         * Sends the connect message
         */
        function _connect()
        {
            _debug('Starting connect');
            var message = {
                channel: '/meta/connect',
                connectionType: _transport.getType()
            };
            _setStatus('connecting');
            _deliver([message], true);
            _setStatus('connected');
        };

        function _send(message)
        {
            if (_batch > 0)
                _messageQueue.push(message);
            else
                _deliver([message], false);
        };

        /**
         * Delivers the messages to the comet server
         * @param messages the array of messages to send
         */
        function _deliver(messages, comet)
        {
            // We must be sure that the messages have a clientId.
            // This is not guaranteed since the handshake may take time to return
            // (and hence the clientId is not known yet) and the application
            // may create other messages.
            $.each(messages, function(index, message)
            {
                message['id'] = _nextMessageId();
                if (_clientId) message['clientId'] = _clientId;
                messages[index] = _applyOutgoingExtensions(message);
            });

            var self = this;
            var envelope = {
                url: _url,
                messages: messages,
                onSuccess: function(request, response)
                {
                    try
                    {
                        _handleSuccess.call(self, request, response, comet);
                    }
                    catch (x)
                    {
                        _debug('Exception during execution of success callback: {}', x);
                    }
                },
                onFailure: function(request, reason, exception)
                {
                    try
                    {
                        _handleFailure.call(self, request, messages, reason, exception, comet);
                    }
                    catch (x)
                    {
                        _debug('Exception during execution of failure callback: {}', x);
                    }
                }
            };
            _debug('Sending request to {}, message(s): {}', envelope.url, JSON.stringify(envelope.messages));
            _transport.send(envelope, comet);
        };

        function _applyIncomingExtensions(message)
        {
            for (var i = 0; i < _extensions.length; ++i)
            {
                var extension = _extensions[i];
                var callback = extension.extension.incoming;
                if (callback && typeof callback === 'function')
                {
                    _debug('Calling incoming extension \'{}\', callback \'{}\'', extension.name, callback.name);
                    message = _applyExtension(extension.name, callback, message) || message;
                }
            }
            return message;
        };

        function _applyOutgoingExtensions(message)
        {
            for (var i = 0; i < _extensions.length; ++i)
            {
                var extension = _extensions[i];
                var callback = extension.extension.outgoing;
                if (callback && typeof callback === 'function')
                {
                    _debug('Calling outgoing extension \'{}\', callback \'{}\'', extension.name, callback.name);
                    message = _applyExtension(extension.name, callback, message) || message;
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
                _debug('Exception during execution of extension \'{}\': {}', name, x);
                return message;
            }
        };

        function _handleSuccess(request, response, comet)
        {
            var messages = _convertToMessages(response);
            _debug('Received response {}', JSON.stringify(messages));

            // Signal the transport it can deliver other queued requests
            _transport.complete(request, true, comet);

            for (var i = 0; i < messages.length; ++i)
            {
                var message = messages[i];
                message = _applyIncomingExtensions(message);

                if (message.advice) _advice = message.advice;

                var channel = message.channel;
                switch (channel)
                {
                    case '/meta/handshake':
                        _handshakeSuccess(message);
                        break;
                    case '/meta/connect':
                        _connectSuccess(message);
                        break;
                    case '/meta/disconnect':
                        _disconnectSuccess(message);
                        break;
                    case '/meta/subscribe':
                        _subscribeSuccess(message);
                        break;
                    case '/meta/unsubscribe':
                        _unsubscribeSuccess(message);
                        break;
                    default:
                        _messageSuccess(message);
                        break;
                }
            }
        };

        function _handleFailure(request, messages, reason, exception, comet)
        {
            var xhr = request.xhr;
            _debug('Request failed, status: {}, reason: {}, exception: {}', xhr && xhr.status, reason, exception);

            // Signal the transport it can deliver other queued requests
            _transport.complete(request, false, comet);

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

        function _handshakeSuccess(message)
        {
            if (message.successful)
            {
                _debug('Handshake successful');
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
                        _debug('Changing transport from {} to {}', _transport.getType(), newTransport.getType());
                        _transport = newTransport;
                    }
                }

                // Notify the listeners
                // Here the new transport is in place, as well as the clientId, so
                // the listener can perform a publish() if it wants, and the listeners
                // are notified before the connect below.
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
                _debug('Handshake unsuccessful');

                var retry = !_isDisconnected() && _advice.reconnect != 'none';
                if (!retry) _setStatus('disconnected');

                _notifyListeners('/meta/handshake', message);
                _notifyListeners('/meta/unsuccessful', message);

                // Only try again if we haven't been disconnected and
                // the advice permits us to retry the handshake
                if (retry)
                {
                    _increaseBackoff();
                    _debug('Handshake failure, backing off and retrying in {} ms', _backoff);
                    _delayedHandshake();
                }
            }
        };

        function _handshakeFailure(xhr, message)
        {
            _debug('Handshake failure');

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
                _debug('Handshake failure, backing off and retrying in {} ms', _backoff);
                _delayedHandshake();
            }
        };

        function _connectSuccess(message)
        {
            var action = _isDisconnected() ? 'none' : (_advice.reconnect ? _advice.reconnect : 'retry');
            if (!_isDisconnected()) _setStatus(action == 'retry' ? 'connecting' : 'disconnecting');

            if (message.successful)
            {
                _debug('Connect successful');

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
                _debug('Connect unsuccessful');

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
                        // End the batch but do not deliver the messages until we connect successfully
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
            _debug('Connect failure');

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
                        _debug('Connect failure, backing off and retrying in {} ms', _backoff);
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
                        _debug('Unrecognized reconnect value: {}', action);
                        break;
                }
            }
        };

        function _disconnectSuccess(message)
        {
            if (message.successful)
            {
                _debug('Disconnect successful');
                _disconnect(false);
                _notifyListeners('/meta/disconnect', message);
            }
            else
            {
                _debug('Disconnect unsuccessful');
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
            _debug('Disconnect failure');
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

        function _subscribeSuccess(message)
        {
            if (message.successful)
            {
                _debug('Subscribe successful');
                _notifyListeners('/meta/subscribe', message);
            }
            else
            {
                _debug('Subscribe unsuccessful');
                _notifyListeners('/meta/subscribe', message);
                _notifyListeners('/meta/unsuccessful', message);
            }
        };

        function _subscribeFailure(xhr, message)
        {
            _debug('Subscribe failure');

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

        function _unsubscribeSuccess(message)
        {
            if (message.successful)
            {
                _debug('Unsubscribe successful');
                _notifyListeners('/meta/unsubscribe', message);
            }
            else
            {
                _debug('Unsubscribe unsuccessful');
                _notifyListeners('/meta/unsubscribe', message);
                _notifyListeners('/meta/unsuccessful', message);
            }
        };

        function _unsubscribeFailure(xhr, message)
        {
            _debug('Unsubscribe failure');

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

        function _messageSuccess(message)
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
                    _debug('Unknown message {}', JSON.stringify(message));
                }
            }
            else
            {
                if (message.successful)
                {
                    _debug('Publish successful');
                    _notifyListeners('/meta/publish', message);
                }
                else
                {
                    _debug('Publish unsuccessful');
                    _notifyListeners('/meta/publish', message);
                    _notifyListeners('/meta/unsuccessful', message);
                }
            }
        };

        function _messageFailure(xhr, message)
        {
            _debug('Publish failure');

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
                            _debug('Notifying subscription: channel \'{}\', callback \'{}\'', channel, subscription.callback.name);
                            subscription.callback.call(subscription.scope, message);
                        }
                        catch (x)
                        {
                            // Ignore exceptions from callbacks
                            _warn('Exception during execution of callback \'{}\' on channel \'{}\' for message {}, exception: {}', subscription.callback.name, channel, JSON.stringify(message), x);
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
            if (_backoff < _maxBackoff) _backoff += _backoffIncrement;
        };

        var _error = this._error = function(text, args)
        {
            _log('error', _format.apply(this, arguments));
        };

        var _warn = this._warn = function(text, args)
        {
            _log('warn', _format.apply(this, arguments));
        };

        var _info = this._info = function(text, args)
        {
            _log('info', _format.apply(this, arguments));
        };

        var _debug = this._debug = function(text, args)
        {
            _log('debug', _format.apply(this, arguments));
        };

        function _log(level, text)
        {
            var priority = _logPriorities[level];
            var configPriority = _logPriorities[_logLevel];
            if (!configPriority) configPriority = _logPriorities['info'];
            if (priority >= configPriority)
            {
                if (window.console) window.console.log(text);
            }
        };

        function _format(text)
        {
            var braces = /\{\}/g;
            var result = '';
            var start = 0;
            var count = 0;
            while (braces.test(text))
            {
                result += text.substr(start, braces.lastIndex - start - 2);
                var arg = arguments[++count];
                result += arg !== undefined ? arg : '{}';
                start = braces.lastIndex;
            }
            result += text.substr(start, text.length - start);
            return result;
        };

        function newLongPollingTransport()
        {
            return $.extend({}, new Transport('long-polling'), new LongPollingTransport());
        };

        function newCallbackPollingTransport()
        {
            return $.extend({}, new Transport('callback-polling'), new CallbackPollingTransport());
        };

        /**
         * Base object with the common functionality for transports.
         * The key responsibility is to allow at most 2 outstanding requests to the server,
         * to avoid that requests are sent behind a long poll.
         * To achieve this, we have one reserved request for the long poll, and all other
         * requests are serialized one after the other.
         */
        var Transport = function(type)
        {
            var _maxRequests = 2;
            var _requestIds = 0;
            var _cometRequest = null;
            var _requests = [];
            var _packets = [];

            this.getType = function()
            {
                return type;
            };

            this.send = function(packet, comet)
            {
                if (comet)
                    _cometSend(this, packet);
                else
                    _send(this, packet);
            };

            function _cometSend(self, packet)
            {
                if (_cometRequest !== null) throw 'Concurrent comet requests not allowed, request ' + _cometRequest.id + ' not yet completed';

                var requestId = ++_requestIds;
                _debug('Beginning comet request {}', requestId);

                var request = {id: requestId};
                _debug('Delivering comet request {}', requestId);
                self.deliver(packet, request);
                _cometRequest = request;
            };

            function _send(self, packet)
            {
                var requestId = ++_requestIds;
                _debug('Beginning request {}, {} other requests, {} queued requests', requestId, _requests.length, _packets.length);

                var request = {id: requestId};
                // Consider the comet request which should always be present
                if (_requests.length < _maxRequests - 1)
                {
                    _debug('Delivering request {}', requestId);
                    self.deliver(packet, request);
                    _requests.push(request);
                }
                else
                {
                    _packets.push([packet, request]);
                    _debug('Queued request {}, {} queued requests', requestId, _packets.length);
                }
            };

            this.complete = function(request, success, comet)
            {
                if (comet)
                    _cometComplete(request);
                else
                    _complete(this, request, success);
            };

            function _cometComplete(request)
            {
                var requestId = request.id;
                if (_cometRequest !== request) throw 'Comet request mismatch, completing request ' + requestId;

                // Reset comet request
                _cometRequest = null;
                _debug('Ended comet request {}', requestId);
            };

            function _complete(self, request, success)
            {
                var requestId = request.id;
                var index = $.inArray(request, _requests);
                // The index can be negative the request has been aborted
                if (index >= 0) _requests.splice(index, 1);
                _debug('Ended request {}, {} other requests, {} queued requests', requestId, _requests.length, _packets.length);

                if (_packets.length > 0)
                {
                    var packet = _packets.shift();
                    if (success)
                    {
                        _debug('Dequeueing and sending request {}, {} queued requests', packet[1].id, _packets.length);
                        _send(self, packet[0]);
                    }
                    else
                    {
                        _debug('Dequeueing and failing request {}, {} queued requests', packet[1].id, _packets.length);
                        // Keep the semantic of calling response callbacks asynchronously after the request
                        setTimeout(function() { packet[0].onFailure(packet[1], 'error'); }, 0);
                    }
                }
            };

            this.abort = function()
            {
                for (var i = 0; i < _requests.length; ++i)
                {
                    var request = _requests[i];
                    _debug('Aborting request {}', request.id);
                    if (request.xhr) request.xhr.abort();
                }
                if (_cometRequest)
                {
                    _debug('Aborting comet request {}', _cometRequest.id);
                    if (_cometRequest.xhr) _cometRequest.xhr.abort();
                }
                _cometRequest = null;
                _requests = [];
                _packets = [];
            };
        };

        var LongPollingTransport = function()
        {
            this.deliver = function(packet, request)
            {
                request.xhr = $.ajax({
                    url: packet.url,
                    type: 'POST',
                    contentType: 'text/json;charset=UTF-8',
                    beforeSend: function(xhr)
                    {
                        xhr.setRequestHeader('Connection', 'Keep-Alive');
                        return true;
                    },
                    data: JSON.stringify(packet.messages),
                    success: function(response) { packet.onSuccess(request, response); },
                    error: function(xhr, reason, exception) { packet.onFailure(request, reason, exception); }
                });
            };
        };

        var CallbackPollingTransport = function()
        {
            var _maxLength = 2000;
            this.deliver = function(packet, request)
            {
                // Microsoft Internet Explorer has a 2083 URL max length
                // We must ensure that we stay within that length
                var messages = JSON.stringify(packet.messages);
                // Encode the messages because all brackets, quotes, commas, colons, etc
                // present in the JSON will be URL encoded, taking many more characters
                var urlLength = packet.url.length + encodeURI(messages).length;
                _debug('URL length: {}', urlLength);
                // Let's stay on the safe side and use 2000 instead of 2083
                // also because we did not count few characters among which
                // the parameter name 'message' and the parameter 'jsonp',
                // which sum up to about 50 chars
                if (urlLength > _maxLength)
                {
                    var x = packet.messages.length > 1 ?
                            'Too many bayeux messages in the same batch resulting in message too big ' +
                            '(' + urlLength + ' bytes, max is ' + _maxLength + ') for transport ' + this.getType() :
                            'Bayeux message too big (' + urlLength + ' bytes, max is ' + _maxLength + ') ' +
                            'for transport ' + this.getType();
                    // Keep the semantic of calling response callbacks asynchronously after the request
                    _setTimeout(function() { packet.onFailure(request, 'error', x); }, 0);
                }
                else
                {
                    $.ajax({
                        url: packet.url,
                        type: 'GET',
                        dataType: 'jsonp',
                        jsonp: 'jsonp',
                        beforeSend: function(xhr)
                        {
                            xhr.setRequestHeader('Connection', 'Keep-Alive');
                            return true;
                        },
                        data:
                        {
                            // In callback-polling, the content must be sent via the 'message' parameter
                            message: messages
                        },
                        success: function(response) { packet.onSuccess(request, response); },
                        error: function(xhr, reason, exception) { packet.onFailure(request, reason, exception); }
                    });
                }
            };
        };
    };

    /**
     * The JS object that exposes the comet API to applications
     */
    $.cometd = new $.Cometd(); // The default instance

})(jQuery);
