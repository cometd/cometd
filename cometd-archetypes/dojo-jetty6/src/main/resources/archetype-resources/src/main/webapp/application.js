dojo.require("dojox.cometd");

dojo.addOnLoad(function()
{
    dojox.cometd.setLogLevel('debug');
    dojox.cometd.addListener('/meta/connect', _onConnect);

    var cometURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
    dojox.cometd.init({
        url: cometURL
    });

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
