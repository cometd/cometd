(function($)
{
    $(document).ready(function()
    {
        // Check if there was a saved application state
        var stateCookie = org.cometd.COOKIE?org.cometd.COOKIE.get('org.cometd.demo.state'):null;
        var state = stateCookie ? org.cometd.JSON.fromJSON(stateCookie) : null;
        var chat = new Chat(state);

        // restore some values
        if (state)
        {
            $('#username').val(state.username);
            $('#useServer').attr('checked',state.useServer);
            $('#altServer').val(state.altServer);
        }

        // Setup UI
        $('#join').show();
        $('#joined').hide();
        $('#altServer').attr('autocomplete', 'off');
        $('#joinButton').click(function() { chat.join($('#username').val()); });
        $('#sendButton').click(chat.send);
        $('#leaveButton').click(chat.leave);
        $('#username').attr('autocomplete', 'off').focus();
        $('#username').keyup(function(e)
        {
            if (e.keyCode == 13)
            {
                chat.join($('#username').val());
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

    function Chat(state)
    {
        var _self = this;
        var _wasConnected = false;
        var _connected = false;
        var _username;
        var _lastUser;
        var _disconnecting;
        var _chatSubscription;
        var _membersSubscription;

        this.join = function(username)
        {
            _disconnecting = false;
            _username = username;
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
                logLevel: 'info'
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
                    membership: 'leave',
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
            _membersSubscription = $.cometd.subscribe('/members/demo', _self.members);
        }

        function _connectionInitialized()
        {
            // first time connection for this client, so subscribe tell everybody.
            $.cometd.batch(function()
            {
                _subscribe();
                $.cometd.publish('/chat/demo', {
                    user: _username,
                    membership: 'join',
                    chat: _username + ' has joined'
                });
            });
        }

        function _connectionEstablished()
        {
            // connection establish (maybe not for first time), so just
            // tell local user and update membership
            _self.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Opened'
                }
            });
            $.cometd.publish('/service/members', {
                user: _username,
                room: '/chat/demo'
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

        function _metaConnect(message)
        {
            if (_disconnecting)
            {
                _connected = false;
                _connectionClosed();
            }
            else
            {
                _wasConnected = _connected;
                _connected = message.successful === true;
                if (!_wasConnected && _connected)
                {
                    _connectionEstablished();
                }
                else if (_wasConnected && !_connected)
                {
                    _connectionBroken();
                }
            }
        }

        function _metaHandshake(message)
        {
            if (message.successful)
            {
                _connectionInitialized();
            }
        }

        $.cometd.addListener('/meta/handshake', _metaHandshake);
        $.cometd.addListener('/meta/connect', _metaConnect);

        // Restore the state, if present
        if (state)
        {
            setTimeout(function()
            {
                // This will perform the handshake
                _self.join(state.username);
            }, 0);
        }

        $(window).unload(function()
        {
            if ($.cometd.reload)
            {
                $.cometd.reload();
                // Save the application state only if the user was chatting
                if (_wasConnected && _username)
                {
                    var expires = new Date();
                    expires.setTime(expires.getTime() + 5 * 1000);
                    org.cometd.COOKIE.set('org.cometd.demo.state', org.cometd.JSON.toJSON({
                        username: _username,
                        useServer: $('#useServer').attr('checked'),
                        altServer: $('#altServer').val()
                    }), { 'max-age': 5, expires: expires });
                }
            }
            else
            {
                $.cometd.disconnect();
            }
        });
    }

})(jQuery);
