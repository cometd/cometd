angular.module('cometdAngularChat', ['cometd-reload'])
    .directive('cometdEnter', function() {
        return function(scope, element, attrs) {
            element.bind("keydown keypress", function(event) {
                if (event.which === 13) {
                    scope.$apply(function() {
                        scope.$eval(attrs.cometdEnter);
                    });
                    event.preventDefault();
                }
            });
        };
    })
    .controller('chatRoom', ['$window', '$scope', '$timeout', 'cometd', function($window, $scope, $timeout, cometd) {
        $scope.join = function() {
            if (!$scope.userName) {
                $window.alert('Please enter a user name');
                return;
            }

            var cometdURL = $window.location.protocol + "//" + $window.location.host + config.contextPath + "/cometd";
            if ($scope.useServer) {
                if ($scope.altServer.length === 0) {
                    alert('Please enter a server address');
                    return;
                }
                cometdURL = $scope.altServer;
            }

            cometd.configure({
                url: cometdURL,
                logLevel: 'debug'
            });
            cometd.handshake();

            $scope.joined = true;
        };

        function _unsubscribe() {
            // TODO: revisit these, perhaps not needed !
            if ($scope.chatSubscription) {
                cometd.unsubscribe($scope.chatSubscription);
            }
            $scope.chatSubscription = null;
            if ($scope.membersSubscription) {
                cometd.unsubscribe($scope.membersSubscription);
            }
            $scope.membersSubscription = null;
        }

        $scope.leave = function() {
            cometd.batch(function() {
                cometd.publish('/chat/demo', {
                    user: $scope.userName,
                    membership: 'leave',
                    chat: $scope.userName + ' has left'
                });
                _unsubscribe();
            });
            cometd.disconnect();

            $scope.joined = false;
            $scope.userName = null;
            $scope.lastUser = null;
            $scope.members = [];
            // TODO
            //$('#username').focus();
        };

        $scope.send = function() {
            var text = $scope.phrase;
            $scope.phrase = '';

            if (!text || !text.length) {
                return;
            }

            var colons = text.indexOf('::');
            if (colons > 0) {
                cometd.publish('/service/privatechat', {
                    room: '/chat/demo',
                    user: $scope.userName,
                    chat: text.substring(colons + 2),
                    peer: text.substring(0, colons)
                });
            } else {
                cometd.publish('/chat/demo', {
                    user: $scope.userName,
                    chat: text
                });
            }
        };

        $scope.receive = function(message) {
            var fromUser = message.data.user;
            var membership = message.data.membership;
            var text = message.data.chat;

            if (!membership && fromUser == $scope.lastUser) {
                fromUser = '...';
            } else {
                $scope.lastUser = fromUser;
                fromUser += ':';
            }

            if (membership) {
                $scope.messages.push({
                    type: 'membership',
                    user: fromUser,
                    text: text
                });
                $scope.lastUser = null;
            } else if (message.data.scope == 'private') {
                $scope.messages.push({
                    type: 'private',
                    user: fromUser,
                    text: text
                });
            } else {
                $scope.messages.push({
                    type: 'regular',
                    user: fromUser,
                    text: text
                });
            }

            // TODO: scroll
            //chat[0].scrollTop = chat[0].scrollHeight - chat.outerHeight();
        };

        $scope.membership = function(message) {
            $scope.members = message.data;
        };

        function _subscribe() {
            $scope.chatSubscription = cometd.subscribe('/chat/demo', $scope.receive);
            $scope.membersSubscription = cometd.subscribe('/members/demo', $scope.membership);
        }

        function _connectionEstablished() {
            // Connection established (maybe not for the first time),
            // so just tell the local user and update membership.
            $scope.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Opened'
                }
            });
            cometd.publish('/service/members', {
                user: $scope.userName,
                room: '/chat/demo'
            });
        }

        function _connectionBroken() {
            $scope.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Broken'
                }
            });
            $scope.members = [];
        }

        function _connectionClosed() {
            $scope.receive({
                data: {
                    user: 'system',
                    chat: 'Connection to Server Closed'
                }
            });
        }

        function _metaHandshake(message) {
            if (message.successful) {
                cometd.batch(function() {
                    _subscribe();
                    cometd.publish('/chat/demo', {
                        user: $scope.userName,
                        membership: 'join',
                        chat: $scope.userName + ' has joined'
                    });
                });
            }
        }

        function _metaConnect(message) {
            if (!$scope.joined) {
                $scope.connected = false;
                _connectionClosed();
            } else {
                var wasConnected = $scope.connected;
                $scope.connected = message.successful === true;
                if (!wasConnected && $scope.connected) {
                    _connectionEstablished();
                } else if (wasConnected && !$scope.connected) {
                    _connectionBroken();
                }
            }
        }

        cometd.addListener('/meta/handshake', _metaHandshake);
        cometd.addListener('/meta/connect', _metaConnect);

        // Setup model.
        $scope.messages = [];
        $scope.members = [];
        $scope.joined = false;
        $scope.altServer = 'http://127.0.0.1:8080/cometd';

        // Check if there was a saved application state.
        var stateKey = 'org.cometd.demo.state';
        var item = $window.sessionStorage.getItem(stateKey);
        if (item) {
            $window.sessionStorage.removeItem(stateKey);
            var state = JSON.parse(item);
            $scope.userName = state.userName;
            $scope.useServer = state.useServer;
            $scope.altServer = state.altServer;
            $timeout(function() {
                // This will perform the handshake.
                $scope.join();
            }, 0, true);
        }

        $window.addEventListener('unload', function() {
            // Save the application state only if the user was chatting.
            if ($scope.userName) {
                cometd.reload();
                $window.sessionStorage.setItem(stateKey, JSON.stringify({
                    userName: $scope.userName,
                    useServer: $scope.useServer,
                    altServer: $scope.altServer
                }));
                cometd.getTransport().abort();
            } else {
                cometd.disconnect();
            }
        });
    }]);
