window.addEventListener('DOMContentLoaded', function() {
    function _id(id) {
        return document.getElementById(id);
    }

    function _empty(element) {
        while (element.hasChildNodes()) {
            element.removeChild(element.lastChild);
        }
    }

    function _show(element) {
        var display = element.getAttribute('data-display');
        // Empty string as display restores the default.
        if (display || display === '') {
            element.style.display = display;
        }
    }

    function _hide(element) {
        element.setAttribute('data-display', element.style.display);
        element.style.display = 'none';
    }

    function Chat(state) {
        var _cometd = new org.cometd.CometD();
        var _self = this;
        var _connected = false;
        var _username;
        var _lastUser;
        var _disconnecting;
        var _chatSubscription;
        var _membersSubscription;

        this.join = function(username) {
            _disconnecting = false;
            _username = username;
            if (!_username) {
                alert('Please enter a username');
                return;
            }

            var cometdURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
            var useServer = _id('useServer').checked;
            if (useServer) {
                var altServer = _id('altServer').value;
                if (altServer.length === 0) {
                    alert('Please enter a server address');
                    return;
                }
                cometdURL = altServer;
            }

            _cometd.configure({
                url: cometdURL,
                useWorkerScheduler: false,
                logLevel: 'debug'
            });
            _cometd.websocketEnabled = false;
            _cometd.unregisterTransport('long-polling');
            _cometd.handshake();

            _hide(_id('join'));
            _show(_id('joined'));
            _id('phrase').focus();
        };

        this.leave = function() {
            _cometd.batch(function() {
                _cometd.publish('/chat/demo', {
                    user: _username,
                    membership: 'leave',
                    chat: _username + ' has left'
                });
                _unsubscribe();
            });
            _cometd.disconnect();

            _show(_id('join'));
            _hide(_id('joined'));
            _id('username').focus();
            _empty(_id('members'));
            _username = null;
            _lastUser = null;
            _disconnecting = true;
        };

        this.send = function() {
            var phrase = _id('phrase');
            var text = phrase.value;
            phrase.value = '';

            if (!text || !text.length) {
                return;
            }

            var colons = text.indexOf('::');
            if (colons > 0) {
                _cometd.publish('/service/privatechat', {
                    room: '/chat/demo',
                    user: _username,
                    chat: text.substring(colons + 2),
                    peer: text.substring(0, colons)
                });
            } else {
                _cometd.publish('/chat/demo', {
                    user: _username,
                    chat: text
                });
            }
        };

        this.receive = function(message) {
            var fromUser = message.data.user;
            var membership = message.data.membership;
            var text = message.data.chat;

            if (!membership && fromUser === _lastUser) {
                fromUser = '...';
            } else {
                _lastUser = fromUser;
                fromUser += ':';
            }

            var chat = _id('chat');

            var spanFrom = document.createElement('span');
            spanFrom.className = 'from';
            spanFrom.appendChild(document.createTextNode(fromUser + '\u00A0'));

            var spanText = document.createElement('span');
            spanText.className = 'text';
            spanText.appendChild(document.createTextNode(message.data.scope === 'private' ? '[private]\u00A0' + text : text));

            if (membership) {
                var spanMembership = document.createElement('span');
                spanMembership.className = 'membership';
                spanMembership.appendChild(spanFrom);
                spanMembership.appendChild(spanText);
                chat.appendChild(spanMembership);
                _lastUser = null;
            } else if (message.data.scope === 'private') {
                var spanPrivate = document.createElement('span');
                spanPrivate.className = 'private';
                spanPrivate.appendChild(spanFrom);
                spanPrivate.appendChild(spanText);
                chat.appendChild(spanPrivate);
            } else {
                chat.appendChild(spanFrom);
                chat.appendChild(spanText);
            }
            chat.appendChild(document.createElement('br'));

            chat.scrollTop = chat.scrollHeight - chat.offsetHeight;
        };

        /**
         * Updates the members list.
         * This function is called when a message arrives on channel /chat/members
         */
        this.members = function(message) {
            var members = _id('members');
            _empty(members);
            for (var i = 0; i < message.data.length; ++i) {
                members.appendChild(document.createElement('span')
                    .appendChild(document.createTextNode(message.data[i]))
                );
                members.appendChild(document.createElement('br'));
            }
        };

        function _unsubscribe() {
            if (_chatSubscription) {
                _cometd.unsubscribe(_chatSubscription);
            }
            _chatSubscription = null;
            if (_membersSubscription) {
                _cometd.unsubscribe(_membersSubscription);
            }
            _membersSubscription = null;
        }

        function _subscribe() {
            _chatSubscription = _cometd.subscribe('/chat/demo', _self.receive);
            _membersSubscription = _cometd.subscribe('/members/demo', _self.members);
        }

        function _connectionInitialized() {
            // first time connection for this client, so subscribe tell everybody.
            _cometd.batch(function() {
                _subscribe();
                _cometd.publish('/chat/demo', {
                    user: _username,
                    membership: 'join',
                    chat: _username + ' has joined'
                });
            });
        }

        function _connectionEstablished() {
            // connection establish (maybe not for first time), so just
            // tell local user and update membership
            _self.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Opened'
                }
            });
            _cometd.publish('/service/members', {
                user: _username,
                room: '/chat/demo'
            });
        }

        function _connectionBroken() {
            _self.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Broken'
                }
            });
            _empty(_id('members'));
        }

        function _connectionClosed() {
            _self.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Closed'
                }
            });
        }

        function _metaConnect(message) {
            if (_disconnecting) {
                _connected = false;
                _connectionClosed();
            } else {
                var wasConnected = _connected;
                _connected = message.successful === true;
                if (!wasConnected && _connected) {
                    _connectionEstablished();
                } else if (wasConnected && !_connected) {
                    _connectionBroken();
                }
            }
        }

        function _metaHandshake(message) {
            if (message.successful) {
                _connectionInitialized();
            }
        }

        _cometd.registerExtension('reload', new org.cometd.ReloadExtension());

        _cometd.addListener('/meta/handshake', _metaHandshake);
        _cometd.addListener('/meta/connect', _metaConnect);

        // Restore the state, if present
        if (state) {
            setTimeout(function() {
                // This will perform the handshake
                _self.join(state.username);
            }, 0);
        }

        window.onunload = function() {
            // Save the application state only if the user was chatting
            if (_username) {
                _cometd.reload();
                window.sessionStorage.setItem(stateKey, JSON.stringify({
                    username: _username,
                    useServer: _id('useServer').prop('checked'),
                    altServer: _id('altServer').val()
                }));
                _cometd.getTransport().abort();
            } else {
                _cometd.disconnect();
            }
        };
    }

    var stateKey = 'org.cometd.demo.state';
    // Check if there was a saved application state
    var jsonState = window.sessionStorage.getItem(stateKey);
    window.sessionStorage.removeItem(stateKey);
    var state = jsonState ? JSON.parse(jsonState) : null;
    var chat = new Chat(state);

    // restore some values
    if (state) {
        _id('username').value = state.username;
        _id('useServer').checked = state.useServer;
        _id('altServer').value = state.altServer;
    }

    // Setup UI
    _show(_id('join'));
    _hide(_id('joined'));
    _id('altServer').autocomplete = 'off';
    _id('joinButton').onclick = function() {
        chat.join(_id('username').value);
    };
    _id('sendButton').onclick = chat.send;
    _id('leaveButton').onclick = chat.leave;
    _id('username').autocomplete = 'off';
    _id('username').focus();
    _id('username').onkeyup = function(e) {
        if (e.key === 'Enter') {
            chat.join(_id('username').value);
        }
    };
    _id('phrase').autocomplete = 'off';
    _id('phrase').onkeyup = function(e) {
        if (e.key === 'Enter') {
            chat.send();
        }
    };
});
