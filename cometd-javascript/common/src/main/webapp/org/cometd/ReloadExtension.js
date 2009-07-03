/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date: 2009-05-10 13:06:45 +1000 (Sun, 10 May 2009) $
 */

if (typeof dojo != "undefined")
    dojo.provide("org.cometd.ReloadExtension");

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
 * This extension allows cometd to be reloaded and to reuse
 * the client ID of the former page.
 *
 * When the reload() method is called, the state of the connection
 * and subscriptions is stored in a cookie with a short max-age.
 * when the new page is loaded
 */
org.cometd.ReloadExtension = function(configuration)
{
    var _cometd;
    var _debug;
    var _state = {hsOut:null,hsIn:null,subs:{}};
    var _cookieMaxAge = configuration && configuration.cookieMaxAge || 5;

    function _reload()
    {
        if (_state && _state.hsIn != null)
        {
            org.cometd.COOKIE.set("org.cometd.reload", org.cometd.JSON.toJSON(_state), {'max-age':_cookieMaxAge});
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
    }

    this.outgoing = function(message)
    {
        var channel = message.channel;
        if (channel == '/meta/handshake')
        {

            _state.hsOut = message;

            var cookie = org.cometd.COOKIE.get("org.cometd.reload");
            if (cookie != null)
            {
                try
                {
                    org.cometd.COOKIE.set("org.cometd.reload", "", {expires:-1});
                    _debug(cookie);
                    var reload = org.cometd.JSON.fromJSON(cookie);

                    if (reload && reload.hsIn && reload.hsOut &&
                        org.cometd.JSON.toJSON(message) == org.cometd.JSON.toJSON(reload.hsOut))
                    {
                        _debug(reload);
                        setTimeout(function()
                        {
                            _state.hsIn = reload.hsIn;
                            _cometd.receive(reload.hsIn);
                        }, 1);
                        return null;
                    }
                }
                catch(x)
                {
                    _debug(x);
                }
            }
            _state.subs = {};
        }
        else if (channel == '/meta/subscribe')
        {
            // consume the subscribe message, as we are already subscribed!
            setTimeout(function()
            {
                _cometd.receive(
                {
                    channel:'/meta/subscribe',
                    subscription:message.subscription,
                    successful: true
                });
            }, 1);
            return null;
        }
        else if (channel == '/meta/disconnect')
        {
            _state = {hsOut:null,hsIn:null,subs:{}};
        }
        return message;
    };

    this.incoming = function(message)
    {
        var channel = message.channel;
        if (channel == '/meta/handshake' && message.successful)
        {
            _state.hsIn = message;
        }
        else if (channel == '/meta/subscribe' && message.successful)
        {
            _state.subs[message.subscription] = true;
        }
        else if (channel == '/meta/unsubscribe' && message.successful)
        {
            delete _state.subs[message.subscription];
        }
        return message;
    };

};
