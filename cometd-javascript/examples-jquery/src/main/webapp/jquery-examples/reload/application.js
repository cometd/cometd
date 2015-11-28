require({
        baseUrl: '../../jquery',
        paths: {
            jquery: 'jquery-2.1.4',
            org: '../org'
        }
    },
    ['jquery', 'jquery.cometd', 'jquery.cometd-reload'],
    function($, cometd) {
        $(document).ready(function() {
            /* handshake listener to report client IDs */
            cometd.addListener('/meta/handshake', function(message) {
                if (message.successful) {
                    $('#previous').html(sessionStorage.getItem('demoLastCometDID'));
                    $('#current').html(message.clientId);
                    sessionStorage.setItem('demoLastCometDID', message.clientId);
                }
                else {
                    $('#previous').html('Handshake Failed');
                    $('#current').html('Handshake Failed');
                }
            });

            /* connect listener to report advice */
            cometd.addListener('/meta/connect', function(message) {
                if (message.advice) {
                    $('#advice').html(org.cometd.JSON.toJSON(message.advice));
                }
            });

            /* Initialize CometD */
            var cometURL = location.href.replace(/\/jquery-examples\/.*$/, '') + '/cometd';
            cometd.init({
                url: cometURL,
                logLevel: 'debug'
            });

            /* Setup reload extension */
            $(window).unload(function() {
                cometd.reload();
            });
        });
    });
