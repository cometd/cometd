/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date$
 */
(function($)
{
    // Remap cometd JSON functions to jquery JSON functions
    org.cometd.JSON.toJSON = $.toJSON;
    org.cometd.JSON.fromJSON = $.secureEvalJSON;

    function _setHeaders(xhr, headers)
    {
        if (headers)
        {
            for (var headerName in headers)
            {
                if (headerName.toLowerCase() === 'content-type')
                {
                    continue;
                }
                xhr.setRequestHeader(headerName, headers[headerName]);
            }
        }
    }

    // The default cometd instance
    $.cometd = new org.cometd.Cometd();

    // Remap toolkit-specific transport calls
    $.cometd.LongPollingTransport = function()
    {
    	org.cometd.LongPollingTransport(this);
        this.xhrSend = function(packet)
        {
            return $.ajax({
                url: packet.url,
                type: 'POST',
                contentType: 'application/json;charset=UTF-8',
                data: packet.body,
                beforeSend: function(xhr)
                {
                    _setHeaders(xhr, packet.headers);
                    // Returning false will abort the XHR send
                    return true;
                },
                success: packet.onSuccess,
                error: function(xhr, reason, exception)
                {
                    packet.onError(reason, exception);
                }
            });
        };
    };

    $.cometd.CallbackPollingTransport = function()
    {
    	org.cometd.CallbackPollingTransport(this);
        this.jsonpSend = function(packet)
        {
            $.ajax({
                url: packet.url,
                type: 'GET',
                dataType: 'jsonp',
                jsonp: 'jsonp',
                data: {
                    // In callback-polling, the content must be sent via the 'message' parameter
                    message: packet.body
                },
                beforeSend: function(xhr)
                {
                    _setHeaders(xhr, packet.headers);
                    // Returning false will abort the XHR send
                    return true;
                },
                success: packet.onSuccess,
                error: function(xhr, reason, exception)
                {
                    packet.onError(reason, exception);
                }
            });
        };
    };

    if (window.WebSocket)
        $.cometd.registerTransport('websocket', new org.cometd.WebSocketTransport());
    $.cometd.registerTransport('long-polling', new $.cometd.LongPollingTransport());
    $.cometd.registerTransport('callback-polling', new $.cometd.CallbackPollingTransport());

})(jQuery);
