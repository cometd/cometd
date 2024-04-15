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

import {CometD} from "../../js/cometd/cometd.js";
import {ReloadExtension} from "../../js/cometd/ReloadExtension.js";

window.addEventListener("DOMContentLoaded", () => {
    const cometd = new CometD();
    cometd.registerExtension("reload", new ReloadExtension());

    // Handshake listener to report client IDs.
    cometd.addListener("/meta/handshake", (message) => {
        if (message.successful) {
            const key = "demoLastCometDID";
            document.getElementById("previous").innerHTML = window.sessionStorage.getItem(key);
            document.getElementById("current").innerHTML = message.clientId;
            window.sessionStorage.setItem(key, message.clientId);
        } else {
            document.getElementById("previous").innerHTML = "Handshake Failed";
            document.getElementById("current").innerHTML = "Handshake Failed";
        }
    });

    // Connect listener to report advice.
    cometd.addListener("/meta/connect", (message) => {
        if (message.advice) {
            document.getElementById("advice").innerHTML = JSON.stringify(message.advice);
        }
    });

    // Initialize CometD.
    const url = location.href.replace(/\/es6-examples\/.*$/, "") + "/cometd";
    cometd.init({
        url: url,
        logLevel: "debug"
    });

    // Set up the reload extension.
    window.onbeforeunload = () => cometd.reload();
});
