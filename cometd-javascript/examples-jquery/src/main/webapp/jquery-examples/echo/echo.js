require({
        baseUrl: "../../js/jquery",
        paths: {
            jquery: "https://code.jquery.com/jquery-3.3.1",
            cometd: "../cometd"
        }
    },
    ["jquery", "jquery.cometd", "jquery.cometd-timestamp", "jquery.cometd-reload"/*, "jquery.cometd-ack"*/],
    function($, cometd) {
        $(function() {
            function echoRpc(text) {
                console.debug("Echoing", text);

                cometd.remoteCall("echo", {msg: text}, function(reply) {
                    var responses = $("#responses");
                    responses.html(responses.html() +
                        (reply.timestamp || "") + " Echoed by server: " + reply.data.msg + "<br/>");
                });
            }

            $(window).on("beforeunload", cometd.reload);

            var phrase = $("#phrase");
            phrase.attr("autocomplete", "OFF");
            phrase.on("keyup", function(e) {
                if (e.keyCode == 13) {
                    echoRpc(phrase.val());
                    phrase.val("");
                    return false;
                }
                return true;
            });
            var sendB = $("#sendB");
            sendB.on("click", function() {
                echoRpc(phrase.val());
                phrase.val("");
                return false;
            });

            cometd.configure({
                url: location.href.replace(/\/jquery-examples\/.*$/, "") + "/cometd",
                logLevel: "debug"
            });

            cometd.addListener("/meta/handshake", function(reply) {
                if (reply.successful) {
                    echoRpc("Type something in the textbox above");
                }
            });
            cometd.handshake();
        });
    });
