/**
 * Base object with the common functionality for transports.
 */
org.cometd.Transport = function()
{
    var _type;
    var _cometd;

    /**
     * Function invoked just after a transport has been successfully registered.
     * @param type the type of transport (for example 'long-polling')
     * @param cometd the cometd object this transport has been registered to
     * @see #unregistered()
     */
    this.registered = function(type, cometd)
    {
        _type = type;
        _cometd = cometd;
    };

    /**
     * Function invoked just after a transport has been successfully unregistered.
     * @see #registered(type, cometd)
     */
    this.unregistered = function()
    {
        _type = null;
        _cometd = null;
    };

    this._debug = function()
    {
        _cometd._debug.apply(_cometd, arguments);
    };

    this._mixin = function()
    {
        return _cometd._mixin.apply(_cometd, arguments);
    };

    this.getConfiguration = function()
    {
        return _cometd.getConfiguration();
    };

    this.getAdvice = function()
    {
        return _cometd.getAdvice();
    };

    this.setTimeout = function(funktion, delay)
    {
        return org.cometd.Utils.setTimeout(_cometd, funktion, delay);
    };

    this.clearTimeout = function(handle)
    {
        org.cometd.Utils.clearTimeout(handle);
    };

    /**
     * Converts the given response into an array of bayeux messages
     * @param response the response to convert
     * @return an array of bayeux messages obtained by converting the response
     */
    this.convertToMessages = function (response)
    {
        if (org.cometd.Utils.isString(response))
        {
            try
            {
                return org.cometd.JSON.fromJSON(response);
            }
            catch(x)
            {
                this._debug('Could not convert to JSON the following string', '"' + response + '"');
                throw x;
            }
        }
        if (org.cometd.Utils.isArray(response))
        {
            return response;
        }
        if (response === undefined || response === null)
        {
            return [];
        }
        if (response instanceof Object)
        {
            return [response];
        }
        throw 'Conversion Error ' + response + ', typeof ' + (typeof response);
    };

    /**
     * Returns whether this transport can work for the given version and cross domain communication case.
     * @param version a string indicating the transport version
     * @param crossDomain a boolean indicating whether the communication is cross domain
     * @return true if this transport can work for the given version and cross domain communication case,
     * false otherwise
     */
    this.accept = function(version, crossDomain, url)
    {
        throw 'Abstract';
    };

    /**
     * Returns the type of this transport.
     * @see #registered(type, cometd)
     */
    this.getType = function()
    {
        return _type;
    };

    this.send = function(envelope, metaConnect)
    {
        throw 'Abstract';
    };

    this.reset = function()
    {
        this._debug('Transport', _type, 'reset');
    };

    this.abort = function()
    {
        this._debug('Transport', _type, 'aborted');
    };

    this.toString = function()
    {
        return this.getType();
    };
};

org.cometd.Transport.derive = function(baseObject)
{
    function F() {}
    F.prototype = baseObject;
    return new F();
};
