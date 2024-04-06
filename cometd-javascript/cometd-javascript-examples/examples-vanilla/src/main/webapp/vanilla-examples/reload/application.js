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
            document.getElementById('advice').innerHTML = JSON.stringify(message.advice);
        }
    });

    // Initialize CometD.
    const url = location.href.replace(/\/vanilla-examples\/.*$/, '') + "/cometd";
    cometd.init({
        url: url,
        logLevel: "debug"
    });

    /* Setup reload extension */
    window.onbeforeunload = () => cometd.reload();
});
