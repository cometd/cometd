/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date: 2009-05-10 13:06:45 +1000 (Sun, 10 May 2009) $
 */

if (typeof dojo != 'undefined')
{
    dojo.provide('org.cometd.ReloadExtension');
}

if (!org.cometd.COOKIE)
{
    org.cometd.COOKIE = {};
    org.cometd.COOKIE.set = function(name, value, options)
    {
        throw 'Abstract';
    };
    org.cometd.COOKIE.get = function(name)
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
org.cometd.ReloadExtension = function(configuration)
{
    var _cometd;
    var _debug;
    var _state = null;
    var _cookieMaxAge = configuration && configuration.cookieMaxAge || 5;
    var _batch = false;

    function _reload()
    {
        if (_state && _state.handshakeResponse !== null)
        {
            var cookie = org.cometd.JSON.toJSON(_state);
            _debug('Reload extension saving cookie value', cookie);
            org.cometd.COOKIE.set('org.cometd.reload', cookie, {
                'max-age': _cookieMaxAge,
                path: '/',
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
        var channel = message.channel;

        if (channel == '/meta/handshake')
        {
            _state = {};
            _state.url = _cometd.getURL();

            var cookie = org.cometd.COOKIE.get('org.cometd.reload');
            _debug('Reload extension found cookie value', cookie);
            // Is there a saved handshake response from a prior load ?
            if (cookie)
            {
                try
                {
                    // Remove the cookie, not needed anymore
                    org.cometd.COOKIE.set('org.cometd.reload', '', {
                        path: '/'
                    });

                    var oldState = org.cometd.JSON.fromJSON(cookie);

                    if (oldState && oldState.handshakeResponse && _similarState(oldState))
                    {
                        _debug('Reload extension restoring state', oldState);
                        setTimeout(function()
                        {
                            _debug('Reload extension replaying handshake response', oldState.handshakeResponse);
                            _state.handshakeResponse = oldState.handshakeResponse;
                            _state.transportType = oldState.transportType;
                            _state.reloading = true;
                            var response = _cometd._mixin(true, {}, _state.handshakeResponse);
                            response.supportedConnectionTypes = [_state.transportType];
                            _cometd.receive(response);
                            _debug('Reload extension replayed handshake response', response);
                        }, 0);
                        
                        // delay any sends until first connect is complete.
                        if (!_batch)
                        {
                            _batch=true;
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
                catch(x)
                {
                    _debug('Reload extension error while trying to restore cookie', x);
                }
            }
        }
        else if (channel == '/meta/connect')
        {
            if (!_state.transportType)
            {
                _state.transportType = message.connectionType;
                _debug('Reload extension tracked transport type', _state.transportType);
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
                    // If the handshake response is already present, then we're replaying it.
                    // Since the replay may have modified the handshake response, do not record it here.
                    if (!_state.handshakeResponse)
                    {
                        // Save successful handshake response
                        _state.handshakeResponse = message;
                        _debug('Reload extension tracked handshake response', message);
                    }
                    break;
                case '/meta/disconnect':
                    _state = null;
                    break;
                case '/meta/connect':
                    if (_batch)
                        _cometd.endBatch();
                    _batch=false;
                    break;
                default:
                    break;
            }
        }
        return message;
    };
};
