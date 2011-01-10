dojo.require("dojox.cometd");

dojo.addOnLoad(function()
{
    var cometd = dojox.cometd;

    function _connectionEstablished()
    {
        dojo.byId('body').innerHTML += '<div>CometD Connection Established</div>';
    }

    function _connectionBroken()
    {
        dojo.byId('body').innerHTML += '<div>CometD Connection Broken</div>';
    }

    function _connectionClosed()
    {
        dojo.byId('body').innerHTML += '<div>CometD Connection Closed</div>';
    }

    // Function that manages the connection status with the Bayeux server
    var _connected = false;
    function _metaConnect(message)
    {
        if (cometd.isDisconnected())
        {
            _connected = false;
            _connectionClosed();
            return;
        }

        var wasConnected = _connected;
        _connected = message.successful === true;
        if (!wasConnected && _connected)
        {
            _connectionEstablished();
        }
        else if (wasConnected && !_connected)
        {
            _connectionBroken();
        }
    }

    // Function invoked when first contacting the server and
    // when the server has lost the state of this client
    function _metaHandshake(handshake)
    {
        if (handshake.successful === true)
        {
            cometd.batch(function()
            {
                cometd.subscribe('/hello', function(message)
                {
                    dojo.byId('body').innerHTML += '<div>Server Says: ' + message.data.greeting + '</div>';
                });
                // Publish on a service channel since the message is for the server only
                cometd.publish('/service/hello', { name: 'World' });
            });
        }
    }

    // Disconnect when the page unloads
    dojo.addOnUnload(function()
    {
        cometd.disconnect(true);
    });

    var cometURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
    cometd.configure({
        url: cometURL,
        logLevel: 'debug'
    });

    cometd.addListener('/meta/handshake', _metaHandshake);
    cometd.addListener('/meta/connect', _metaConnect);

    cometd.handshake();
});
