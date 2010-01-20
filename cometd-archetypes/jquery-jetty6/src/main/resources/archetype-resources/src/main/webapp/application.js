(function($)
{
    var cometd = $.cometd;

    $(document).ready(function()
    {
        // Idempotent function called every time the connection
        // with the Bayeux server is (re-)established
        var _subscription;
        function _connectionSucceeded()
        {
            $('#body').empty().append('<div>Cometd Connection Succeeded</div>');

            cometd.batch(function()
            {
                if (_subscription)
                {
                    cometd.unsubscribe(_subscription);
                }
                _subscription = cometd.subscribe('/hello', function(message)
                {
                    $('#body').append('<div>Server Says: ' + message.data.greeting + '</div>');
                });
                // Publish on a service channel since the message is for the server only
                cometd.publish('/service/hello', { name: 'World' });
            });
        }

        function _connectionBroken()
        {
            $('#body').empty().html('Cometd Connection Broken');
        }

        // Function that manages the connection status with the Bayeux server
        var _connected = false;
        function _metaConnect(message)
        {
            var wasConnected = _connected;
            _connected = message.successful === true;
            if (!wasConnected && _connected)
            {
                _connectionSucceeded();
            }
            else if (wasConnected && !_connected)
            {
                _connectionBroken();
            }
        }

        // Disconnect when the page unloads
        $(window).unload(function()
        {
            cometd.disconnect();
        });

        var cometURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
        cometd.configure({
            url: cometURL,
            logLevel: 'debug'
        });

        cometd.addListener('/meta/connect', _metaConnect);

        cometd.handshake();
    });
})(jQuery);
