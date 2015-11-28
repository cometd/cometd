require({
        baseUrl: '../..',
        // Specify 'paths' since CometD is not bundled with Dojo anymore
        paths: {
            'dojox/cometd': 'dojox/cometd'
        }
    },
    ['dojox/cometd', 'dojo/dom', 'dojo/_base/unload', 'dojox/cometd/reload'],
    function(cometd, dom, unload) {
        /* handshake listener to report client IDs */
        cometd.addListener("/meta/handshake", function(message) {
            if (message.successful) {
                dom.byId('previous').innerHTML = sessionStorage.getItem('demoLastCometDID');
                dom.byId('current').innerHTML = message.clientId;
                sessionStorage.setItem('demoLastCometDID', message.clientId);
            }
            else {
                dom.byId('previous').innerHTML = 'Handshake Failed';
                dom.byId('current').innerHTML = 'Handshake Failed';
            }
        });

        /* connect listener to report advice */
        cometd.addListener("/meta/connect", function(message) {
            if (message.advice) {
                dom.byId('advice').innerHTML = JSON.stringify(message.advice);
            }
        });

        /* Initialize CometD */
        var cometURL = location.href.replace(/\/dojo-examples\/.*$/, '') + "/cometd";
        cometd.init({
            url: cometURL,
            logLevel: "debug"
        });

        /* Setup reload extension */
        unload.addOnUnload(cometd, "reload");
    });
