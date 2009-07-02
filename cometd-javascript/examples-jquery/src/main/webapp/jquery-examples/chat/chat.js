var chat = function()
{
    var _username;
    var _lastUser;
    var _chatSubscription;
    var _metaSubscriptions = [];
    var _handshook = false;
    var _connected = false;
    var _cometd;

    return {
        init: function()
        {
//            _comet = new $.Cometd(); // Creates a new Comet object
            _cometd = $.cometd; // Uses the default Comet object

            $('#join').show();
            $('#joined').hide();
            $('#username').focus();
            $('#altServer').attr('autocomplete', 'OFF');

            $('#joinB').click(function(e)
            {
                join();
                e.preventDefault();
            });
            $('#leaveB').click(leave);

            $('#username').attr({
                'autocomplete': 'OFF'
            }).keyup(function(e)
            {
                if (e.keyCode == 13)
                {
                    join();
                    e.preventDefault();
                }
            });
            $('#phrase').attr({
                'autocomplete': 'OFF'
            }).keyup(function(e)
            {
                if (e.keyCode == 13)
                {
                    send();
                    e.preventDefault();
                }
            });
            $('#sendB').click(send);

            $(window).unload(leave);
        }
    }

    function _chatUnsubscribe()
    {
        if (_chatSubscription) _cometd.unsubscribe(_chatSubscription);
        _chatSubscription = null;
    }

    function _chatSubscribe()
    {
        _chatUnsubscribe();
        _chatSubscription = _cometd.subscribe('/chat/demo', this, receive);
    }

    function _metaUnsubscribe()
    {
        $.each(_metaSubscriptions, function(index, subscription)
        {
            _cometd.removeListener(subscription);
        });
        _metaSubscriptions = [];
    }

    function _metaSubscribe()
    {
        _metaUnsubscribe();
        _metaSubscriptions.push(_cometd.addListener('/meta/handshake', this, _metaHandshake));
        _metaSubscriptions.push(_cometd.addListener('/meta/connect', this, _metaConnect));
    }

    function _metaHandshake(message)
    {
        _handshook = message.successful;
        _connected = false;
        receive({
            data: {
                user: 'CHAT',
                join: true,
                chat: 'Handshake ' + (_handshook ? 'successful' : 'unsuccessful')
            }
        });
    }

    function _metaConnect(message)
    {
        var wasConnected = _connected;
        _connected = message.successful;
        if (wasConnected)
        {
            if (_connected)
            {
                // Normal operation, a long poll that reconnects
            }
            else
            {
                // Disconnected
                receive({
                    data: {
                        user: 'CHAT',
                        join: true,
                        chat: 'Disconnected'
                    }
                });
            }
        }
        else
        {
            if (_connected)
            {
                receive({
                    data: {
                        user: 'CHAT',
                        join: true,
                        chat: 'Connected'
                    }
                });
                _cometd.startBatch();
                _chatSubscribe();
                _cometd.publish('/chat/demo', {
                    user: _username,
                    join: true,
                    chat: _username + ' has joined'
                });
                _cometd.endBatch();
            }
            else
            {
                // Could not connect
                receive({
                    data: {
                        user: 'CHAT',
                        join: true,
                        chat: 'Could not connect'
                    }
                });
            }
        }
    }

    function join()
    {
        _username = $('#username').val();
        if (!_username)
        {
            alert('Please enter a username!');
            return;
        }


        var altServer = $('#altServer').val();
	// temporary fix for context path
        var cometURL = (altServer.length > 0)
	               ? altServer
		       : (new String(document.location).replace(/http:\/\/[^\/]*/, '').replace(/\/jquery-examples\/.*$/, '')) + "/cometd";

        // Subscribe for meta channels immediately so that the chat knows about meta channel events
        _metaSubscribe();
        _cometd.init(cometURL);

        $('#join').hide();
        $('#joined').show();
        $('#phrase').focus();
    }

    function leave()
    {
        if (!_username) return;

        _cometd.startBatch();
        _cometd.publish('/chat/demo', {
            user: _username,
            leave: true,
            chat: _username + ' has left'
        });
        _chatUnsubscribe();
        _cometd.endBatch();

        _metaUnsubscribe();

        // switch the input form
        $('#join').show();
        $('#joined').hide();
        $('#username').focus();
        _username = null;
        _cometd.disconnect();
        $('#members').html('');
    }

    function send()
    {
        var phrase = $('#phrase');
        var text = phrase.val();
        phrase.val('');

        if (!text || !text.length) return;

        var colons = text.indexOf('::');
        if (colons > 0)
        {
            _cometd.publish('/service/privatechat', {
                room: '/chat/demo', // This should be replaced by the room name
                user: _username,
                chat: text.substring(colons + 2),
                peer: text.substring(0, colons)
            });
        }
        else
        {
            _cometd.publish('/chat/demo', {
                user: _username,
                chat: text
            });
        }
    }

    function receive(message)
    {
        if (message.data instanceof Array)
        {
            var list = '';
            $.each(message.data, function(index, datum)
            {
                list += datum + '<br />';
            });
            $('#members').html(list);
        }
        else
        {
            var chat = $('#chat');

            var fromUser = message.data.user;
            var membership = message.data.join || message.data.leave;
            var text = message.data.chat;
            if (!text) return;

            if (!membership && fromUser == _lastUser)
            {
                fromUser = '...';
            }
            else
            {
                _lastUser = fromUser;
                fromUser += ':';
            }

            if (membership)
            {
                chat.append('<span class=\"membership\"><span class=\"from\">' + fromUser + '&nbsp;</span><span class=\"text\">' + text + '</span></span><br/>');
                _lastUser = '';
            }
            else if (message.data.scope == 'private')
            {
                chat.append('<span class=\"private\"><span class=\"from\">' + fromUser + '&nbsp;</span><span class=\"text\">[private]&nbsp;' + text + '</span></span><br/>');
            }
            else
            {
                chat.append('<span class=\"from\">' + fromUser + '&nbsp;</span><span class=\"text\">' + text + '</span><br/>');
            }

            // There seems to be no easy way in jQuery to handle the scrollTop property
            chat[0].scrollTop = chat[0].scrollHeight - chat.outerHeight();
        }
    }
}();
