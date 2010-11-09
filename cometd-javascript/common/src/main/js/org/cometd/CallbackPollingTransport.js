org.cometd.CallbackPollingTransport = function()
{
    var _super = new org.cometd.RequestTransport();
    var that = org.cometd.Transport.derive(_super);
    var _maxLength = 2000;

    that.accept = function(version, crossDomain)
    {
        return true;
    };

    that.jsonpSend = function(packet)
    {
        throw 'Abstract';
    };

    that.transportSend = function(envelope, request)
    {
        var self = this;

        // Microsoft Internet Explorer has a 2083 URL max length
        // We must ensure that we stay within that length
        var messages = org.cometd.JSON.toJSON(envelope.messages);
        // Encode the messages because all brackets, quotes, commas, colons, etc
        // present in the JSON will be URL encoded, taking many more characters
        var urlLength = envelope.url.length + encodeURI(messages).length;

        // Let's stay on the safe side and use 2000 instead of 2083
        // also because we did not count few characters among which
        // the parameter name 'message' and the parameter 'jsonp',
        // which sum up to about 50 chars
        if (urlLength > _maxLength)
        {
            var x = envelope.messages.length > 1 ?
                    'Too many bayeux messages in the same batch resulting in message too big ' +
                    '(' + urlLength + ' bytes, max is ' + _maxLength + ') for transport ' + this.getType() :
                    'Bayeux message too big (' + urlLength + ' bytes, max is ' + _maxLength + ') ' +
                    'for transport ' + this.getType();
            // Keep the semantic of calling response callbacks asynchronously after the request
            this.setTimeout(function()
            {
                self.transportFailure(envelope, request, 'error', x);
            }, 0);
        }
        else
        {
            try
            {
                var sameStack = true;
                this.jsonpSend({
                    transport: this,
                    url: envelope.url,
                    sync: envelope.sync,
                    headers: this.getConfiguration().requestHeaders,
                    body: messages,
                    onSuccess: function(responses)
                    {
                        var success = false;
                        try
                        {
                            var received = self.convertToMessages(responses);
                            if (received.length === 0)
                            {
                                self.transportFailure(envelope, request, 'no response', null);
                            }
                            else
                            {
                                success=true;
                                self.transportSuccess(envelope, request, received);
                            }
                        }
                        catch (x)
                        {
                            self._debug(x);
                            if (!success)
                            {
                                self.transportFailure(envelope, request, 'bad response', x);
                            }
                        }
                    },
                    onError: function(reason, exception)
                    {
                        if (sameStack)
                        {
                            // Keep the semantic of calling response callbacks asynchronously after the request
                            this.setTimeout(function()
                            {
                                self.transportFailure(envelope, request, reason, exception);
                            }, 0);
                        }
                        else
                        {
                            self.transportFailure(envelope, request, reason, exception);
                        }
                    }
                });
                sameStack = false;
            }
            catch (xx)
            {
                // Keep the semantic of calling response callbacks asynchronously after the request
                this.setTimeout(function()
                {
                    self.transportFailure(envelope, request, 'error', xx);
                }, 0);
            }
        }
    };

    return that;
};
