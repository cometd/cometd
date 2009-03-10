(function($)
{
    /**
     * This client-side extension enables the client to acknowledge to the server
     * the messages that the client has received.
     * For the acknowledgement to work, the server must be configured with the
     * correspondent server-side ack extension. If both client and server support
     * the ack extension, then the ack functionality will take place automatically.
     * By enabling this extension, all messages arriving from the server will arrive
     * via the long poll, so the comet communication will be slightly chattier.
     * Messages are not acknowledged one by one, but instead a group of messages is
     * acknowledged when long poll returns.
     */
    $.Cometd.AckExtension = function(name, cometd)
    {
        var _name = name || 'ack';
        var _serverSupportsAcks = false;
        var _ackId = -1;

        if (cometd) cometd.registerExtension(_name, this);

        this.incoming = function(message)
        {
            var channel = message.channel;
            if (channel == '/meta/handshake')
            {
                _serverSupportsAcks = message.ext && message.ext.ack;
            }
            else if (_serverSupportsAcks && channel == '/meta/connect' && message.successful)
            {
                var ext = message.ext;
                if (ext && typeof ext.ack === 'number')
                {
                    _ackId = ext.ack;
                    _debug('AckExtension: server sent ack id {}', _ackId);
                }
            }
            return message;
        };

        this.outgoing = function(message)
        {
            var channel = message.channel;
            if (channel == '/meta/handshake')
            {
                if (!message.ext) message.ext = {};
                message.ext.ack = true;
                _ackId = -1;
            }
            else if (_serverSupportsAcks && channel == '/meta/connect')
            {
                if (!message.ext) message.ext = {};
                message.ext.ack = _ackId;
                _debug('AckExtension: client sending ack id {}', _ackId);
            }
            return message;
        };

        function _debug(text, args)
        {
            if (cometd) cometd._debug(text, args);
        }
    };

    // Register the ack extension on the default cometd object
    new $.Cometd.AckExtension('ack', $.cometd);

})(jQuery);
