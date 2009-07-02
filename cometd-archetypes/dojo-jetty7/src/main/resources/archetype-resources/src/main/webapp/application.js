dojo.require("dojox.cometd");

dojo.addOnLoad(function()
{
    var cometd = dojox.cometd;
    dojo.addOnUnload(function()
    {
        cometd.disconnect();
    });

    var cometURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
    cometd.configure({
        url: cometURL,
        logLevel: 'debug'
    });

    cometd.addListener('/meta/connect', _onConnect);

    cometd.handshake();

    var _connected = false;
    function _onConnect(message)
    {
        var wasConnected = _connected;
        _connected = message.successful;
        if (!wasConnected && _connected)
        {
            dojo.byId('body').innerHTML = 'Cometd Configured Successfully';
        }
    };
});
