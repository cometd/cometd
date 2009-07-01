(function($)
{
    $(document).ready(function()
    {
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

        cometd.addListener('/meta/connect', _onConnect);

        cometd.handshake();

        var _connected = false;
        function _onConnect(message)
        {
            var wasConnected = _connected;
            _connected = message.successful;
            if (!wasConnected && _connected)
            {
                $('#body').html('Cometd Configured Successfully');
            }
        };
    });
})(jQuery);