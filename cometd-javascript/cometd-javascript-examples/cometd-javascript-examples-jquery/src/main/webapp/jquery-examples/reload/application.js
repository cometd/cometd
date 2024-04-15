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
import {ReloadExtension} from "cometd/ReloadExtension.js";

$(function() {
    cometd.registerExtension("reload", new ReloadExtension());

    // Handshake listener to report client IDs.
    cometd.addListener("/meta/handshake", (message) => {
        if (message.successful) {
            const key = "demoLastCometDID";
            $("#previous").html(window.sessionStorage.getItem(key));
            $("#current").html(message.clientId);
            window.sessionStorage.setItem(key, message.clientId);
        } else {
            $("#previous").html("Handshake Failed");
            $("#current").html("Handshake Failed");
        }
    });

    // Connect listener to report advice.
    cometd.addListener("/meta/connect", (message) => {
        if (message.advice) {
            $("#advice").html(JSON.stringify(message.advice));
        }
    });

    // Initialize CometD.
    const url = location.href.replace(/\/jquery-examples\/.*$/, "") + "/cometd";
    cometd.init({
        url: url,
        logLevel: "debug"
    });

    // Set up the reload extension.
    $(window).on("unload", () => {
        cometd.reload();
    });
});
