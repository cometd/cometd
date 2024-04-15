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
import {AckExtension} from "cometd/AckExtension.js";
import {ReloadExtension} from "cometd/ReloadExtension.js";

const STATE_KEY = "org.cometd.demo.state";

$(() => {
    // Check if there was a saved application state.
    const jsonState = window.sessionStorage.getItem(STATE_KEY);
    window.sessionStorage.removeItem(STATE_KEY);
    const state = jsonState ? JSON.parse(jsonState) : null;

    const chat = new Chat();

    // Restore the state, if present.
    if (state) {
        setTimeout(() => {
            // This will perform the handshake.
            chat.join(state.userName);
        }, 0);

        $("#username").val(state.userName);
        $("#useServer").prop("checked", state.useServer);
        $("#altServer").val(state.altServer);
    }

    // Set up the UI.
    $("#join").show();
    $("#joined").hide();
    $("#altServer").prop("autocomplete", "off");
    $("#joinButton").click(() => {
        chat.join($("#username").val());
    });
    $("#sendButton").click(() => chat.send());
    $("#leaveButton").click(() => chat.leave());
    $("#username").prop("autocomplete", "off").focus();
    $("#username").keyup((e) => {
        if (e.key === "Enter") {
            chat.join($("#username").val());
        }
    });
    $("#phrase").prop("autocomplete", "off");
    $("#phrase").keyup((e) => {
        if (e.key === "Enter") {
            chat.send();
        }
    });
});

class Chat {
    #connected = false;
    #userName;
    #lastUserName;
    #disconnecting = false;
    #chatSubscription;
    #membersSubscription;

    constructor() {
        cometd.registerExtension("ack", new AckExtension());
        cometd.registerExtension("reload", new ReloadExtension());
        cometd.addListener("/meta/handshake", (m) => this.#metaHandshake(m));
        cometd.addListener("/meta/connect", (m) => this.#metaConnect(m));

        $(window).on("unload", () => {
            // Save the application state only if the user was chatting.
            if (this.#userName) {
                cometd.reload();
                window.sessionStorage.setItem(STATE_KEY, JSON.stringify({
                    userName: this.#userName,
                    useServer: $("#useServer").prop("checked"),
                    altServer: $("#altServer").val()
                }));
                cometd.getTransport().abort();
            } else {
                cometd.disconnect();
            }
        });
    }

    join(userName) {
        this.#disconnecting = false;
        this.#userName = userName;
        if (!userName) {
            alert("Please enter a user name");
            return;
        }

        let cometdURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
        const useServer = $("#useServer").prop("checked");
        if (useServer) {
            const altServer = $("#altServer").val();
            if (altServer.length === 0) {
                alert("Please enter a server address");
                return;
            }
            cometdURL = altServer;
        }

        cometd.configure({
            url: cometdURL,
            logLevel: "debug"
        });
        cometd.handshake();

        $("#join").hide();
        $("#joined").show();
        $("#phrase").focus();
    };

    leave() {
        cometd.batch(() => {
            cometd.publish("/chat/demo", {
                user: this.#userName,
                membership: "leave",
                chat: this.#userName + " has left"
            });
            this.#unsubscribe();
        });
        cometd.disconnect();

        $("#join").show();
        $("#joined").hide();
        $("#username").focus();
        $("#members").empty();
        this.#userName = null;
        this.#lastUserName = null;
        this.#disconnecting = true;
    };

    send() {
        const phrase = $("#phrase");
        const text = phrase.val();
        phrase.val("");

        if (!text || !text.length) {
            return;
        }

        const colons = text.indexOf("::");
        if (colons > 0) {
            cometd.publish("/service/privatechat", {
                room: "/chat/demo",
                user: this.#userName,
                chat: text.substring(colons + 2),
                peer: text.substring(0, colons)
            });
        } else {
            cometd.publish("/chat/demo", {
                user: this.#userName,
                chat: text
            });
        }
    };

    #receive(message) {
        let fromUser = message.data.user;
        const membership = message.data.membership;
        const text = message.data.chat;

        if (!membership && fromUser === this.#lastUserName) {
            fromUser = "...";
        } else {
            this.#lastUserName = fromUser;
            fromUser += ":";
        }

        const chat = $("#chat");

        if (membership) {
            chat.append("<span class=\"membership\"><span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">" + text + "</span></span><br/>");
            this.#lastUserName = null;
        } else if (message.data.scope === "private") {
            chat.append("<span class=\"private\"><span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">[private]&nbsp;" + text + "</span></span><br/>");
        } else {
            chat.append("<span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">" + text + "</span><br/>");
        }

        // There seems to be no easy way in jQuery to handle the scrollTop property
        chat[0].scrollTop = chat[0].scrollHeight - chat.outerHeight();
    };

    /**
     * Updates the members list.
     * This method is called when a message is received on channel "/chat/members".
     */
    #members(message) {
        let list = "";
        $.each(message.data, (i, e) => {
            list += e + "<br />";
        });
        $("#members").html(list);
    };

    #unsubscribe() {
        if (this.#chatSubscription) {
            cometd.unsubscribe(this.#chatSubscription);
        }
        this.#chatSubscription = null;
        if (this.#membersSubscription) {
            cometd.unsubscribe(this.#membersSubscription);
        }
        this.#membersSubscription = null;
    }

    #subscribe() {
        this.#chatSubscription = cometd.subscribe("/chat/demo", (m) => this.#receive(m));
        this.#membersSubscription = cometd.subscribe("/members/demo", (m) => this.#members(m));
    }

    #connectionInitialized() {
        // First time connection for this client, so subscribe tell everybody.
        cometd.batch(() => {
            this.#subscribe();
            cometd.publish("/chat/demo", {
                user: this.#userName,
                membership: "join",
                chat: this.#userName + " has joined"
            });
        });
    }

    #connectionEstablished() {
        // The connection is established (maybe not for first time),
        // just tell local user and update membership.
        this.#receive({
            data: {
                user: "system",
                chat: "Connection to Server Opened"
            }
        });
        cometd.publish("/service/members", {
            user: this.#userName,
            room: "/chat/demo"
        });
    }

    #connectionBroken() {
        this.#receive({
            data: {
                user: "system",
                chat: "Connection to Server Broken"
            }
        });
        $("#members").empty();
    }

    #connectionClosed() {
        this.#receive({
            data: {
                user: "system",
                chat: "Connection to Server Closed"
            }
        });
    }

    #metaConnect(message) {
        if (this.#disconnecting) {
            this.#connected = false;
            this.#connectionClosed();
        } else {
            const wasConnected = this.#connected;
            this.#connected = message.successful === true;
            if (!wasConnected && this.#connected) {
                this.#connectionEstablished();
            } else if (wasConnected && !this.#connected) {
                this.#connectionBroken();
            }
        }
    }

    #metaHandshake(message) {
        if (message.successful) {
            this.#connectionInitialized();
        }
    }
}
