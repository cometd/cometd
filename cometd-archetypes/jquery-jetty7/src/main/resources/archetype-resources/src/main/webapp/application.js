(function($)
{
    $(document).ready(function()
    {
        var _connected = false;

        function _connectionSucceeded()
        {
            $('#body').empty().html('Cometd Connection Succeeded');
        }

        function _connectionBroken()
        {
            $('#body').empty().html('Cometd Connection Broken');
        }

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

        var cometd = $.cometd;

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