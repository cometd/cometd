dojo.provide("dojox.cometd._base");

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

dojox.cometd = new org.cometd.Cometd();
