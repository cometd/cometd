require(["dojo", "dojo/on", "dojo/keys", "dojox/cometd", "dojox/cometd/timestamp", "dojox/cometd/reload", "dojo/domReady!"],
    function(dojo, on, keys, cometd) {
        function echoRpc(text) {
            console.debug("Echoing", text);

            cometd.remoteCall("echo", {msg: text}, function(reply) {
                dojo.byId("responses").innerHTML +=
                    (reply.timestamp || "") + " Echoed by server: " + reply.data.msg + "<br/>";
            });
        }

        on(window, "beforeunload", cometd.reload);

        var phrase = dojo.byId("phrase");
        phrase.setAttribute("autocomplete", "OFF");
        on(phrase, "keyup", function(e) {
            if (e.keyCode == keys.ENTER) {
                echoRpc(phrase.value);
                phrase.value = "";
                return false;
            }
            return true;
        });
        var sendB = dojo.byId("sendB");
        on(sendB, "click", function() {
            echoRpc(phrase.value);
            phrase.value = "";
            return false;
        });

        cometd.configure({
            url: location.href.replace(/\/dojo-examples\/.*$/, "") + "/cometd",
            logLevel: "debug"
        });

        cometd.addListener("/meta/handshake", function(reply) {
            if (reply.successful) {
                echoRpc("Type something in the textbox above");
            }
        });
        cometd.handshake();
    });
