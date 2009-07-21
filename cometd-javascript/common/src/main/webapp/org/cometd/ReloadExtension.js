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
    var _state = {
        handshakeRequest: null,
        handshakeResponse: null,
        subscriptions: {}
    };
    var _cookieMaxAge = configuration && configuration.cookieMaxAge || 5;

    function _reload()
    {
        if (_state && _state.handshakeResponse !== null)
        {
            org.cometd.COOKIE.set('org.cometd.reload', org.cometd.JSON.toJSON(_state), {'max-age': _cookieMaxAge});
            _state = {};
        }
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
            _state.handshakeRequest = message;
            _state.subscriptions = {};
            var cookie = org.cometd.COOKIE.get('org.cometd.reload');
            // Is there a saved handshake response from a prior load ?
            if (cookie)
            {
                try
                {
                    org.cometd.COOKIE.set('org.cometd.reload', '', {expires:-1});
                    _debug('Reload extension found cookie', cookie);
                    var reload = org.cometd.JSON.fromJSON(cookie);

                    // If the handshake matches the save copy, send the prior handshake response.
                    if (reload && reload.handshakeResponse && reload.handshakeRequest &&
                        org.cometd.JSON.toJSON(message) == org.cometd.JSON.toJSON(reload.handshakeRequest))
                    {
                        _debug('Reload extension restoring state', reload);
                        setTimeout(function()
                        {
                            _state.handshakeResponse = reload.handshakeResponse;
                            _state.subscriptions = reload.subscriptions;
                            _cometd.receive(reload.handshakeResponse);
                        }, 0);
                        // This handshake is aborted, as we will replay the prior handshake response
                        return null;
                    }
                }
                catch(x)
                {
                    _debug('Reload extension error while trying to restore cookie', x);
                }
            }
        }
        else if (channel == '/meta/subscribe')
        {
            // Are we already subscribed ?
            if (_state.subscriptions[message.subscription])
            {
                // Consume the subscribe message, as we are already subscribed
                setTimeout(function()
                {
                    _cometd.receive({
                        channel: '/meta/subscribe',
                        subscription: message.subscription,
                        successful: true
                    });
                }, 0);

                _debug('Reload extension will replay subscription to', message.subscription);
                // This subscription is aborted, as we will replay a previous one
                return null;
            }
        }
        else if (channel == '/meta/disconnect')
        {
            _state = {
                handshakeRequest: null,
                handshakeResponse: null,
                subscriptions: {}
            };
        }
        return message;
    };

    this.incoming = function(message)
    {
        if (message.successful)
        {
            switch(message.channel)
            {
                case '/meta/handshake':
                    // Save successful handshake response
                    _state.handshakeResponse = message;
                    break;
                case '/meta/subscribe':
                    // Track subscriptions
                    _state.subscriptions[message.subscription] = true;
                    break;
                case '/meta/unsubscribe':
                    // Track unsubscriptions
                    delete _state.subscriptions[message.subscription];
                    break;
                default:
                    break;
            }
        }
        return message;
    };
};
