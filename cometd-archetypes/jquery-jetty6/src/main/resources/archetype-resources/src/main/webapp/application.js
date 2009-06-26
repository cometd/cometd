(function($)
{
    $(document).ready(function()
    {
        $.cometd.setLogLevel('debug');
        $.cometd.addListener('/meta/connect', _onConnect);

        var cometURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
        $.cometd.init({
            url: cometURL
        });

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