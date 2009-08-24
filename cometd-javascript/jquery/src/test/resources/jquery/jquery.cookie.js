/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date$
 */
(function($)
{
    var _defaultConfig = {
        'max-age' : 365,
        path : '/'
    };

    function _set(key, value, options)
    {
        var o = $.extend({}, _defaultConfig, options);
        if (value === null || value === undefined)
        {
            value = '';
            o.expires = -1;
        }

        if (typeof o.expires === 'number')
        {
            // Assume it's the number of days from now
            var today = new Date();
            today.setDate(today.getDate() + o.expires);
            o.expires = today;
        }
        var expires;
        if (o.expires && o.expires.toUTCString)
        {
            expires = o.expires.toUTCString();
        }

        // Create the cookie string
        var result = key + '=' + encodeURIComponent(value);
        if (expires)
        {
            result += '; expires=' + expires;
        }
        if (o.path)
        {
            result += '; path=' + (o.path);
        }
        if (o.domain)
        {
            result += '; domain=' + (o.domain);
        }
        if (o.secure)
        {
            result +='; secure';
        }

        document.cookie = result;
    }

    function _get(key)
    {
        var cookies = document.cookie.split(';');
        for (var i = 0; i < cookies.length; ++i)
        {
            var cookie = $.trim(cookies[i]);
            if (cookie.substring(0, key.length + 1) == (key + '='))
            {
                return decodeURIComponent(cookie.substring(key.length + 1));
            }
        }
        return null;
    }

    $.cookie = function(key, value, options)
    {
        if (arguments.length > 1)
        {
            _set(key, value, options);
            return undefined;
        }
        else
        {
            return _get(key);
        }
    };

})(jQuery);
