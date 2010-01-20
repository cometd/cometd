/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date: 2009-05-10 13:06:45 +1000 (Sun, 10 May 2009) $
 */

if (typeof dojo!="undefined")
{
    dojo.provide("org.cometd.AckExtension");
}

/**
 * This client-side extension enables the client to acknowledge to the server
 * the messages that the client has received.
 * For the acknowledgement to work, the server must be configured with the
 * correspondent server-side ack extension. If both client and server support
 * the ack extension, then the ack functionality will take place automatically.
 * By enabling this extension, all messages arriving from the server will arrive
 * via the long poll, so the comet communication will be slightly chattier.
 * The fact that all messages will return via long poll means also that the
 * messages will arrive with total order, which is not guaranteed if messages
 * can arrive via both long poll and normal response.
 * Messages are not acknowledged one by one, but instead a group of messages is
 * acknowledged when long poll returns.
 */
org.cometd.AckExtension = function()
{
    var _cometd;
    var _serverSupportsAcks = false;
    var _ackId = -1;

    function _debug(text, args)
    {
        _cometd._debug(text, args);
    }

    this.registered = function(name, cometd)
    {
        _cometd = cometd;
        _debug('AckExtension: executing registration callback');
    };

    this.unregistered = function()
    {
        _debug('AckExtension: executing unregistration callback');
        _cometd = null;
    };

    this.incoming = function(message)
    {
        var channel = message.channel;
        if (channel == '/meta/handshake')
        {
            _serverSupportsAcks = message.ext && message.ext.ack;
            _debug('AckExtension: server supports acks', _serverSupportsAcks);
        }
        else if (_serverSupportsAcks && channel == '/meta/connect' && message.successful)
        {
            var ext = message.ext;
            if (ext && typeof ext.ack === 'number')
            {
                _ackId = ext.ack;
                _debug('AckExtension: server sent ack id', _ackId);
            }
        }
        return message;
    };

    this.outgoing = function(message)
    {
        var channel = message.channel;
        if (channel == '/meta/handshake')
        {
            if (!message.ext)
            {
                message.ext = {};
            }
            message.ext.ack = _cometd && _cometd.ackEnabled !== false;
            _ackId = -1;
        }
        else if (_serverSupportsAcks && channel == '/meta/connect')
        {
            if (!message.ext)
            {
                message.ext = {};
            }
            message.ext.ack = _ackId;
            _debug('AckExtension: client sending ack id', _ackId);
        }
        return message;
    };
};
