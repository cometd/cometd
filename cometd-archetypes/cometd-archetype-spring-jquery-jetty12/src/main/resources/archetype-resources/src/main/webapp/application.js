import "jquery";
import "jquery/cometd";

$(() => {
    let _connected = false;

    function _connectionEstablished() {
        $("#body").append("<div>CometD Connection Established</div>");
    }

    function _connectionBroken() {
        $("#body").append("<div>CometD Connection Broken</div>");
    }

    function _connectionClosed() {
        $("#body").append("<div>CometD Connection Closed</div>");
    }

    // Function that manages the connection status with the Bayeux server
    function _metaConnect(message) {
        if ($.cometd.isDisconnected()) {
            _connected = false;
            _connectionClosed();
            return;
        }

        const wasConnected = _connected;
        _connected = message.successful === true;
        if (!wasConnected && _connected) {
            _connectionEstablished();
        } else if (wasConnected && !_connected) {
            _connectionBroken();
        }
    }

    // Function invoked when first contacting the server and
    // when the server has lost the state of this client.
    function _metaHandshake(handshake) {
        if (handshake.successful === true) {
            $.cometd.batch(() => {
                $.cometd.subscribe("/hello", (message) => {
                    $("#body").append(`<div>Server Says: ${message.data.greeting}</div>`);
                });
                // Publish on a service channel since the message is for the server only
                $.cometd.publish("/service/hello", {name: "World"});
            });
        }
    }

    // Disconnect when the page unloads.
    $(window).on("unload", () => $.cometd.disconnect());

    const cometURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
    $.cometd.configure({
        url: cometURL,
        logLevel: "debug"
    });

    $.cometd.addListener("/meta/handshake", _metaHandshake);
    $.cometd.addListener("/meta/connect", _metaConnect);

    $.cometd.handshake();
});
