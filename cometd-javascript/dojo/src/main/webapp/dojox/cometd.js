/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date: 2009-05-10 13:06:45 +1000 (Sun, 10 May 2009) $
 */

dojo.provide('dojox.cometd');
dojo.registerModulePath('org','../org');
dojo.require('org.cometd');
dojo.require('dojo.io.script');

// Remap cometd JSON functions to dojo JSON functions
org.cometd.JSON.toJSON = dojo.toJson;
org.cometd.JSON.fromJSON = dojo.fromJson;

// Remap comet AJAX functions to dojo AJAX functions
org.cometd.AJAX.send = function(packet)
{
    var transportType = packet.transport.getType();
    if (transportType == 'long-polling')
    {
        var deferred = dojo.rawXhrPost({
            url: packet.url,
            contentType: 'text/json;charset=UTF-8',
            headers: packet.headers,
            postData: packet.body,
            handleAs: 'json',
            load: packet.onSuccess,
            error: function(error) { packet.onError(error.message, deferred.ioArgs.error); }
        });
        return deferred.ioArgs.xhr;
    }
    else if (transportType == 'callback-polling')
    {
        dojo.io.script.get({
            url: packet.url,
            callbackParamName: 'jsonp',
            content: {
                // In callback-polling, the content must be sent via the 'message' parameter
                message: packet.body
            },
            load: packet.onSuccess,
            error: function(error) { packet.onError(error.message, deferred.ioArgs.error); }
        });
        return undefined;
    }
    else
    {
        throw 'Unsupported transport ' + transportType;
    }
};

// The default cometd instance
dojox.cometd = new org.cometd.Cometd();

// Create a compatability API for dojox.cometd instance with
// the original API.

dojox.cometd._init=dojox.cometd.init;

dojox.cometd.init = function(configuration, handshakeProps)
{
    dojox.cometd._init(configuration, handshakeProps);
}

dojox.cometd._unsubscribe=dojox.cometd.unsubscribe;

dojox.cometd.unsubscribe=function(channelOrToken,objOrFunc,funcName)
{
    if (typeof channelOrToken == "string")
	throw "deprecated unsubscribe. - please pass token return from subscribe";
    dojox.cometd._unsubscribe(channelOrToken);
}

dojox.cometd._metaHandshakeEvent=function(event)
{
    event.action="handshake";
    dojo.publish("/cometd/meta",[event]);
}

dojox.cometd._metaConnectEvent=function(event)
{
    event.action="connect";
    dojo.publish("/cometd/meta",[event]);
}

dojox.cometd.addListener('/meta/handshake', dojox.cometd, dojox.cometd._metaHandshakeEvent);
dojox.cometd.addListener('/meta/connect', dojox.cometd, dojox.cometd._metaConnectEvent);

