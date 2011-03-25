/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 */

dojo.provide('dojox.cometd');
dojo.registerModulePath('org','../org');
dojo.require('org.cometd');
dojo.require('dojo.io.script');

// Remap cometd JSON functions to dojo JSON functions
org.cometd.JSON.toJSON = dojo.toJson;
org.cometd.JSON.fromJSON = dojo.fromJson;

dojox.Cometd = function(name)
{
    var cometd = new org.cometd.Cometd(name);

    function LongPollingTransport()
    {
        var _super = new org.cometd.LongPollingTransport();
        var that = org.cometd.Transport.derive(_super);

        that.xhrSend = function(packet)
        {
            var deferred = dojo.rawXhrPost({
                url: packet.url,
                sync: packet.sync === true,
                contentType: 'application/json;charset=UTF-8',
                headers: packet.headers,
                postData: packet.body,
                handleAs: 'json',
                load: packet.onSuccess,
                error: function(error)
                {
                    packet.onError(error.message, deferred ? deferred.ioArgs.error : error);
                }
            });
            return deferred.ioArgs.xhr;
        };

        return that;
    }

    function CallbackPollingTransport()
    {
        var _super = new org.cometd.CallbackPollingTransport();
        var that = org.cometd.Transport.derive(_super);

        that.jsonpSend = function(packet)
        {
            var deferred = dojo.io.script.get({
                url: packet.url,
                sync: packet.sync === true,
                callbackParamName: 'jsonp',
                content: {
                    // In callback-polling, the content must be sent via the 'message' parameter
                    message: packet.body
                },
                load: packet.onSuccess,
                error: function(error)
                {
                    packet.onError(error.message, deferred ? deferred.ioArgs.error : error);
                }
            });
            return undefined;
        };

        return that;
    }

    if (window.WebSocket)
    {
        cometd.registerTransport('websocket', new org.cometd.WebSocketTransport());
    }
    cometd.registerTransport('long-polling', new LongPollingTransport());
    cometd.registerTransport('callback-polling', new CallbackPollingTransport());

    return cometd;
};

// The default cometd instance
dojox.cometd = new dojox.Cometd();

// Create a compatibility API for dojox.cometd instance with
// the original API.

dojox.cometd._init = dojox.cometd.init;

dojox.cometd._unsubscribe = dojox.cometd.unsubscribe;

dojox.cometd.unsubscribe = function(channelOrToken, objOrFunc, funcName)
{
    if (typeof channelOrToken === 'string')
    {
        throw "Deprecated function unsubscribe(string). Use unsubscribe(object) passing as argument the return value of subscribe()";
    }

    dojox.cometd._unsubscribe(channelOrToken);
};

dojox.cometd._metaHandshakeEvent = function(event)
{
    event.action = "handshake";
    dojo.publish("/cometd/meta", [event]);
};

dojox.cometd._metaConnectEvent = function(event)
{
    event.action = "connect";
    dojo.publish("/cometd/meta", [event]);
};

dojox.cometd.addListener('/meta/handshake', dojox.cometd, dojox.cometd._metaHandshakeEvent);
dojox.cometd.addListener('/meta/connect', dojox.cometd, dojox.cometd._metaConnectEvent);
