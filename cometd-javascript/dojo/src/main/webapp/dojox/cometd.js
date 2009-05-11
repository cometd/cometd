// stub loader for the cometd module since no implementation code is allowed to live in top-level files
dojo.provide("dojox.cometd");
dojo.registerModulePath("org","../org");
dojo.require("org.cometd");

// Remap cometd JSON functions to dojo JSON functions
org.cometd.JSON.toJSON = dojo.toJson;

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
