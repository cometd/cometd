(function($)
{
    $(document).ready(function()
    {
        var chat = new Chat();

        // Setup UI
        $('#join').show();
        $('#joined').hide();
        $('#altServer').attr('autocomplete', 'off');
        $('#joinButton').click(chat.join);
        $('#sendButton').click(chat.send);
        $('#leaveButton').click(chat.leave);
        $('#username').attr('autocomplete', 'off').focus();
        $('#username').keyup(function(e)
        {
            if (e.keyCode == 13)
            {
                chat.join();
            }
        });
        $('#phrase').attr('autocomplete', 'off');
        $('#phrase').keyup(function(e)
        {
            if (e.keyCode == 13)
            {
                chat.send();
            }
        });
    });

    function Chat()
    {
        var _self = this;
        var _username;
        var _lastUser;
        var _disconnecting;
        var _chatSubscription;
        var _membersSubscription;

        this.join = function()
        {
            _disconnecting = false;
            _username = $('#username').val();
            if (!_username)
            {
                alert('Please enter a username');
                return;
            }

            var cometdURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
            var useServer = $('#useServer').attr('checked');
            if (useServer)
            {
                var altServer = $('#altServer').val();
                if (altServer.length == 0)
                {
                    alert('Please enter a server address');
                    return;
                }
                cometdURL = altServer;
            }

            $.cometd.configure({
                url: cometdURL,
                logLevel: 'debug'
            });
            $.cometd.handshake();

            $('#join').hide();
            $('#joined').show();
            $('#phrase').focus();
        };

        this.leave = function()
        {
            $.cometd.batch(function()
            {
                $.cometd.publish('/chat/demo', {
                    user: _username,
                    chat: _username + ' has left'
                });
                _unsubscribe();
            });
            $.cometd.disconnect();

            $('#join').show();
            $('#joined').hide();
            $('#username').focus();
            $('#members').empty();
            _username = null;
            _lastUser = null;
            _disconnecting = true;
        };

        this.send = function()
        {
            var phrase = $('#phrase');
            var text = phrase.val();
            phrase.val('');

            if (!text || !text.length) return;

            var colons = text.indexOf('::');
            if (colons > 0)
            {
                $.cometd.publish('/service/privatechat', {
                    room: '/chat/demo',
                    user: _username,
                    chat: text.substring(colons + 2),
                    peer: text.substring(0, colons)
                });
            }
            else
            {
                $.cometd.publish('/chat/demo', {
                    user: _username,
                    chat: text
                });
            }
        };

        this.receive = function(message)
        {
            var fromUser = message.data.user;
            var membership = message.data.membership;
            var text = message.data.chat;

            if (!membership && fromUser == _lastUser)
            {
                fromUser = '...';
            }
            else
            {
                _lastUser = fromUser;
                fromUser += ':';
            }

            var chat = $('#chat');
            if (membership)
            {
                chat.append('<span class=\"membership\"><span class=\"from\">' + fromUser + '&nbsp;</span><span class=\"text\">' + text + '</span></span><br/>');
                _lastUser = null;
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
        };

        /**
         * Updates the members list.
         * This function is called when a message arrives on channel /chat/members
         */
        this.members = function(message)
        {
            var list = '';
            $.each(message.data, function()
            {
                list += this + '<br />';
            });
            $('#members').html(list);
        };

        function _unsubscribe()
        {
            if (_chatSubscription)
            {
                $.cometd.unsubscribe(_chatSubscription);
            }
            _chatSubscription = null;
            if (_membersSubscription)
            {
                $.cometd.unsubscribe(_membersSubscription);
            }
            _membersSubscription = null;
        }

        function _subscribe()
        {
            _chatSubscription = $.cometd.subscribe('/chat/demo', _self.receive);
            _membersSubscription = $.cometd.subscribe('/chat/members', _self.members);
        }

        function _connectionEstablished()
        {
            _self.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Opened'
                }
            });
            $.cometd.batch(function()
            {
                _unsubscribe();
                _subscribe();
                $.cometd.publish('/service/members', {
                    user: _username,
                    room: '/chat/demo'
                });
                $.cometd.publish('/chat/demo', {
                    user: _username,
                    membership: 'join',
                    chat: _username + ' has joined'
                });
            });
        }

        function _connectionBroken()
        {
            _self.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Broken'
                }
            });
            $('#members').empty();
        }

        function _connectionClosed()
        {
            _self.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Closed'
                }
            });
        }

        var _connected = false;
        function _metaConnect(message)
        {
            if (_disconnecting)
            {
                _connected = false;
                _connectionClosed();
            }
            else
            {
                var wasConnected = _connected;
                _connected = message.successful === true;
                if (!wasConnected && _connected)
                {
                    _connectionEstablished();
                }
                else if (wasConnected && !_connected)
                {
                    _connectionBroken();
                }
            }
        }

        $.cometd.addListener('/meta/connect', _metaConnect);

        // Disconnect when the page unloads
        $(window).unload(_self.leave);
    }

})(jQuery);




