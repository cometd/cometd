angular.module('cometdReloadApp', ['cometd-reload'])
    .controller('cometdReload', ['$window', '$scope', 'cometd', function($window, $scope, cometd) {
        /* /meta/handshake listener to report client IDs */
        cometd.addListener('/meta/handshake', function(message) {
            if (message.successful) {
                $scope.previous = $window.sessionStorage.getItem('demoLastCometDID');
                $scope.current = message.clientId;
                $window.sessionStorage.setItem('demoLastCometDID', message.clientId);
            }
            else {
                $scope.previous = 'Handshake Failed';
                $scope.current = 'Handshake Failed';
            }
        });

        /* /meta/connect listener to report advice */
        cometd.addListener('/meta/connect', function(message) {
            if (message.advice) {
                $scope.advice = JSON.stringify(message.advice);
            }
        });

        /* Initialize CometD */
        var cometURL = location.href.replace(/\/angular-examples\/.*$/, '') + '/cometd';
        cometd.init({
            url: cometURL,
            logLevel: 'debug'
        });

        /* Setup reload extension */
        $window.addEventListener('unload', function() {
            cometd.reload();
        });
    }]);
