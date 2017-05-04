require({
        baseUrl: '../../js/jquery',
        paths: {
            jquery: 'https://code.jquery.com/jquery-3.2.1',
            cometd: '../cometd'
        }
    },
    ['jquery', 'jquery.cometd', 'jquery.cometd-reload'],
    function($, cometd) {
        $(function() {
            /* handshake listener to report client IDs */
            cometd.addListener('/meta/handshake', function(message) {
                if (message.successful) {
                    $('#previous').html(window.sessionStorage.getItem('demoLastCometDID'));
                    $('#current').html(message.clientId);
                    window.sessionStorage.setItem('demoLastCometDID', message.clientId);
                } else {
                    $('#previous').html('Handshake Failed');
                    $('#current').html('Handshake Failed');
                }
            });

            /* connect listener to report advice */
            cometd.addListener('/meta/connect', function(message) {
                if (message.advice) {
                    $('#advice').html(JSON.stringify(message.advice));
                }
            });

            /* Initialize CometD */
            var cometURL = location.href.replace(/\/jquery-examples\/.*$/, '') + '/cometd';
            cometd.init({
                url: cometURL,
                logLevel: 'debug'
            });

            /* Setup reload extension */
            $(window).on('unload', function() {
                cometd.reload();
            });
        });
    });
