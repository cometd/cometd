/*
 * Copyright (c) 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import "jquery";
import {cometd} from "jquery/cometd";

$(() => {
    function echoRpc(text) {
        console.debug("Echoing", text);

        cometd.remoteCall("echo", {msg: text}, (reply) => {
            const responses = $("#responses");
            responses.html(responses.html() +
                (reply.timestamp || "") + " Echoed by server: " + reply.data.msg + "<br/>");
        });
    }

    $(window).on("beforeunload", cometd.reload);

    const phrase = $("#phrase");
    phrase.attr("autocomplete", "OFF");
    phrase.on("keyup", (e) => {
        if (e.key === 'Enter') {
            echoRpc(phrase.val());
            phrase.val("");
            return false;
        }
        return true;
    });
    const sendB = $("#sendB");
    sendB.on("click", () => {
        echoRpc(phrase.val());
        phrase.val("");
        return false;
    });

    cometd.configure({
        url: location.href.replace(/\/jquery-examples\/.*$/, "") + "/cometd",
        logLevel: "debug"
    });

    cometd.addListener("/meta/handshake", (reply) => {
        if (reply.successful) {
            echoRpc("Type something in the textbox above");
        }
    });
    cometd.handshake();
});
