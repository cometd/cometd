(function($)
{
    // Remap cometd JSON functions to jquery JSON functions
    org.cometd.JSON.toJSON = $.toJSON;

    // Remap cometd AJAX functions to jquery AJAX functions
    org.cometd.AJAX.send = function(packet)
    {
        var transportType = packet.transport.getType();
        if (transportType == 'long-polling')
        {
            return $.ajax({
                url: packet.url,
                type: 'POST',
                contentType: 'text/json;charset=UTF-8',
                data: packet.body,
                beforeSend: function(xhr)
                {
                    _setHeaders(xhr, packet.headers);
                    // Returning false will abort the XHR send
                    return true;
                },
                success: packet.onSuccess,
                error: function(xhr, reason, exception) { packet.onError(reason, exception); }
            });
        }
        else if (transportType == 'callback-polling')
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
                error: function(xhr, reason, exception) { packet.onError(reason, exception); }
            });
        }
        else
        {
            throw 'Unsupported transport ' + transportType;
        }
    };

    function _setHeaders(xhr, headers)
    {
        if (headers)
        {
            for (var headerName in headers)
            {
                if (headerName.toLowerCase() === 'content-type') continue;
                xhr.setRequestHeader(headerName, headers[headerName]);
            }
        }
    };

    // The default cometd instance
    $.cometd = new org.cometd.Cometd();

})(jQuery);