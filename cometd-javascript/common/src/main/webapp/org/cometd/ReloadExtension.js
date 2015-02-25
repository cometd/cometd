/*
 * Copyright (c) 2010 the original author or authors.
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

(function()
{
    function bind(org_cometd)
    {
        if (!org_cometd.COOKIE)
        {
            org_cometd.COOKIE = {};
            org_cometd.COOKIE.set = function(name, value, options)
            {
                throw 'Abstract';
            };
            org_cometd.COOKIE.get = function(name)
            {
                throw 'Abstract';
            };
        }

        /**
         * The reload extension allows a page to be loaded (or reloaded)
         * without having to re-handshake in the new (or reloaded) page,
         * therefore resuming the existing cometd connection.
         *
         * When the reload() method is called, the state of the cometd
         * connection and of the cometd subscriptions is stored in a cookie
         * with a short max-age.
         * The reload() method must therefore be called by page unload
         * handlers, often provided by JavaScript toolkits.
         *
         * When the page is (re)loaded, this extension checks the cookie
         * and restores the cometd connection and the cometd subscriptions.
         */
        return org_cometd.ReloadExtension = function(configuration)
        {
            var _cometd;
            var _debug;
            var _state = {};
            var _cookieName = 'org.cometd.reload';
            var _cookiePath = '/';
            var _cookieMaxAge = 5;
            var _batch = false;
            var _reloading = false;

            function _reload(config)
            {
                if (_state.handshakeResponse)
                {
                    _reloading = true;
                    var transport = _cometd.getTransport();
                    if (transport)
                    {
                        transport.abort();
                    }
                    _configure(config);
                    _state.cookiePath = _cookiePath;
                    var cookie = org_cometd.JSON.toJSON(_state);
                    _debug('Reload extension saving cookie value', cookie);
                    org_cometd.COOKIE.set(_cookieName, cookie, {
                        'max-age': _cookieMaxAge,
                        path: _cookiePath,
                        expires: new Date(new Date().getTime() + _cookieMaxAge * 1000)
                    });
                }
            }

            function _similarState(oldState)
            {
                // We want to check here that the CometD object
                // did not change much between reloads.
                // We just check the URL for now, but in future
                // further checks may involve the transport type
                // and other configuration parameters.
                return _state.url == oldState.url;
            }

            function _configure(config)
            {
                if (config)
                {
                    if (typeof config.cookieMaxAge === 'number')
                    {
                        _cookieMaxAge = config.cookieMaxAge;
                    }
                    if (typeof config.cookieName === 'string')
                    {
                        _cookieName = config.cookieName;
                    }
                    if (typeof config.cookiePath === 'string')
                    {
                        _cookiePath = config.cookiePath;
                    }
                }
            }

            this.configure = _configure;

            this.registered = function(name, cometd)
            {
                _cometd = cometd;
                _cometd.reload = _reload;
                _debug = _cometd._debug;
            };

            this.unregistered = function()
            {
                delete _cometd.reload;
                _cometd = null;
            };

            this.outgoing = function(message)
            {
                switch (message.channel)
                {
                    case '/meta/handshake':
                    {
                        _state = {};
                        _state.url = _cometd.getURL();

                        var cookie = org_cometd.COOKIE.get(_cookieName);
                        _debug('Reload extension found cookie value', cookie);
                        // Is there a saved handshake response from a prior load ?
                        if (cookie)
                        {
                            try
                            {
                                var oldState = org_cometd.JSON.fromJSON(cookie);

                                // Remove the cookie, not needed anymore
                                org_cometd.COOKIE.set(_cookieName, '', {
                                    'max-age': -1,
                                    path: oldState.cookiePath,
                                    expires: -1
                                });

                                if (oldState.handshakeResponse && _similarState(oldState))
                                {
                                    _debug('Reload extension restoring state', oldState);

                                    // Since we are going to abort this message,
                                    // we must save an eventual callback to restore
                                    // it when we replay the handshake response.
                                    var callback = _cometd._getCallback(message.id);

                                    setTimeout(function ()
                                    {
                                        _debug('Reload extension replaying handshake response', oldState.handshakeResponse);
                                        _state.handshakeResponse = oldState.handshakeResponse;
                                        _state.transportType = oldState.transportType;

                                        // Restore the callback.
                                        _cometd._putCallback(message.id, callback);

                                        var response = _cometd._mixin(true, {}, _state.handshakeResponse, {
                                            // Keep the response message id the same as the request.
                                            id: message.id,
                                            // Tells applications this is a handshake replayed by the reload extension.
                                            ext: { reload: true }
                                        });
                                        // Use the same transport as before.
                                        response.supportedConnectionTypes = [_state.transportType];

                                        _cometd.receive(response);
                                        _debug('Reload extension replayed handshake response', response);
                                    }, 0);

                                    // Delay any sends until first connect is complete.
                                    // This avoids that there is an old /meta/connect pending on server
                                    // that will be resumed to send messages to the client, when the
                                    // client has already closed the connection, thereby losing the messages.
                                    if (!_batch)
                                    {
                                        _batch = true;
                                        _cometd.startBatch();
                                    }

                                    // This handshake is aborted, as we will replay the prior handshake response
                                    return null;
                                }
                                else
                                {
                                    _debug('Reload extension could not restore state', oldState);
                                }
                            }
                            catch (x)
                            {
                                _debug('Reload extension error while trying to restore cookie', x);
                            }
                        }
                        break;
                    }
                    case '/meta/connect':
                    {
                        if (_reloading === true)
                        {
                            // The reload causes the failure of the outstanding /meta/connect,
                            // which CometD will react to by sending another. Here we avoid
                            // that /meta/connect messages are sent between the reload and
                            // the destruction of the JavaScript context, so that we are sure
                            // that the first /meta/connect is the one triggered after the
                            // replay of the /meta/handshake by this extension.
                            _debug('Reload extension aborting /meta/connect during reload');
                            return null;
                        }

                        if (!_state.transportType)
                        {
                            _state.transportType = message.connectionType;
                            _debug('Reload extension tracked transport type', _state.transportType);
                        }
                        break;
                    }
                    case '/meta/disconnect':
                    {
                        _state = {};
                        break;
                    }
                    default:
                    {
                        break;
                    }
                }
                return message;
            };

            this.incoming = function(message)
            {
                if (message.successful)
                {
                    switch (message.channel)
                    {
                        case '/meta/handshake':
                        {
                            // If the handshake response is already present, then we're replaying it.
                            // Since the replay may have modified the handshake response, do not record it here.
                            if (!_state.handshakeResponse)
                            {
                                // Save successful handshake response
                                _state.handshakeResponse = message;
                                _debug('Reload extension tracked handshake response', message);
                            }
                            break;
                        }
                        case '/meta/connect':
                        {
                            if (_batch)
                            {
                                _batch = false;
                                _cometd.endBatch();
                            }
                            break;
                        }
                        case '/meta/disconnect':
                        {
                            _state = {};
                            break;
                        }
                        default:
                        {
                            break;
                        }
                    }
                }
                return message;
            };

            _configure(configuration);
        };
    }

    if (typeof define === 'function' && define.amd)
    {
        define(['org/cometd'], bind);
    }
    else
    {
        bind(org.cometd);
    }
})();
