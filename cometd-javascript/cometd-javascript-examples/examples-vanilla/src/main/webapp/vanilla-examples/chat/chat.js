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
import {AckExtension} from "../../js/cometd/AckExtension.js";
import {ReloadExtension} from "../../js/cometd/ReloadExtension.js";

const STATE_KEY = "org.cometd.demo.state";

window.addEventListener("DOMContentLoaded", () => {
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

        _id("username").value = state.userName;
        _id("useServer").checked = state.useServer;
        _id("altServer").value = state.altServer;
    }

    // Set up the UI.
    _show(_id("join"));
    _hide(_id("joined"));
    _id("altServer").autocomplete = "off";
    _id("joinButton").onclick = () => {
        chat.join(_id("username").value);
    };
    _id("sendButton").onclick = () => chat.send();
    _id("leaveButton").onclick = () => chat.leave();
    _id("username").autocomplete = "off";
    _id("username").focus();
    _id("username").onkeyup = (e) => {
        if (e.key === "Enter") {
            chat.join(_id("username").value);
        }
    };
    _id("phrase").autocomplete = "off";
    _id("phrase").onkeyup = (e) => {
        if (e.key === "Enter") {
            chat.send();
        }
    };
});

class Chat {
    #cometd = new CometD();
    #connected = false;
    #userName;
    #lastUserName;
    #disconnecting = false;
    #chatSubscription;
    #membersSubscription;

    constructor() {
        this.#cometd.registerExtension("ack", new AckExtension());
        this.#cometd.registerExtension("reload", new ReloadExtension());
        this.#cometd.addListener("/meta/handshake", (m) => this.#metaHandshake(m));
        this.#cometd.addListener("/meta/connect", (m) => this.#metaConnect(m));

        window.onbeforeunload = () => {
            // Save the application state only if the user was chatting.
            if (this.#userName) {
                this.#cometd.reload();
                window.sessionStorage.setItem(STATE_KEY, JSON.stringify({
                    userName: this.#userName,
                    useServer: _id("useServer").checked,
                    altServer: _id("altServer").value
                }));
                this.#cometd.getTransport().abort();
            } else {
                this.#cometd.disconnect();
            }
        };
    }

    join(userName) {
        this.#disconnecting = false;
        this.#userName = userName;
        if (!userName) {
            alert("Please enter a user name");
            return;
        }

        let cometdURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
        const useServer = _id("useServer").checked;
        if (useServer) {
            const altServer = _id("altServer").value;
            if (altServer.length === 0) {
                alert("Please enter a server address");
                return;
            }
            cometdURL = altServer;
        }

        this.#cometd.configure({
            url: cometdURL,
            useWorkerScheduler: false,
            logLevel: "debug"
        });
        this.#cometd.websocketEnabled = false;
        this.#cometd.unregisterTransport("long-polling");
        this.#cometd.handshake();

        _hide(_id("join"));
        _show(_id("joined"));
        _id("phrase").focus();
    };

    leave() {
        this.#cometd.batch(() => {
            this.#cometd.publish("/chat/demo", {
                user: this.#userName,
                membership: "leave",
                chat: this.#userName + " has left"
            });
            this.#unsubscribe();
        });
        this.#cometd.disconnect();

        _show(_id("join"));
        _hide(_id("joined"));
        _id("username").focus();
        _empty(_id("members"));

        this.#userName = null;
        this.#lastUserName = null;
        this.#disconnecting = true;
    };

    send() {
        const phrase = _id("phrase");
        const text = phrase.value;
        phrase.value = "";

        if (!text || !text.length) {
            return;
        }

        const colons = text.indexOf("::");
        if (colons > 0) {
            this.#cometd.publish("/service/privatechat", {
                room: "/chat/demo",
                user: this.#userName,
                chat: text.substring(colons + 2),
                peer: text.substring(0, colons)
            });
        } else {
            this.#cometd.publish("/chat/demo", {
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

        const chat = _id("chat");

        const spanFrom = document.createElement("span");
        spanFrom.className = "from";
        spanFrom.appendChild(document.createTextNode(fromUser + "\u00A0"));

        const spanText = document.createElement("span");
        spanText.className = "text";
        spanText.appendChild(document.createTextNode(message.data.scope === "private" ? "[private]\u00A0" + text : text));

        if (membership) {
            const spanMembership = document.createElement("span");
            spanMembership.className = "membership";
            spanMembership.appendChild(spanFrom);
            spanMembership.appendChild(spanText);
            chat.appendChild(spanMembership);
            this.#lastUserName = null;
        } else if (message.data.scope === "private") {
            const spanPrivate = document.createElement("span");
            spanPrivate.className = "private";
            spanPrivate.appendChild(spanFrom);
            spanPrivate.appendChild(spanText);
            chat.appendChild(spanPrivate);
        } else {
            chat.appendChild(spanFrom);
            chat.appendChild(spanText);
        }
        chat.appendChild(document.createElement("br"));

        chat.scrollTop = chat.scrollHeight - chat.offsetHeight;
    };

    /**
     * Updates the members list.
     * This method is called when a message is received on channel "/chat/members".
     */
    #members(message) {
        const members = _id("members");
        _empty(members);
        for (let i = 0; i < message.data.length; ++i) {
            members.appendChild(document.createElement("span")
                .appendChild(document.createTextNode(message.data[i]))
            );
            members.appendChild(document.createElement("br"));
        }
    };

    #unsubscribe() {
        if (this.#chatSubscription) {
            this.#cometd.unsubscribe(this.#chatSubscription);
        }
        this.#chatSubscription = null;
        if (this.#membersSubscription) {
            this.#cometd.unsubscribe(this.#membersSubscription);
        }
        this.#membersSubscription = null;
    }

    #subscribe() {
        this.#chatSubscription = this.#cometd.subscribe("/chat/demo", (m) => this.#receive(m));
        this.#membersSubscription = this.#cometd.subscribe("/members/demo", (m) => this.#members(m));
    }

    #connectionInitialized() {
        // First time connection for this client, so subscribe tell everybody.
        this.#cometd.batch(() => {
            this.#subscribe();
            this.#cometd.publish("/chat/demo", {
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
        this.#cometd.publish("/service/members", {
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
        _empty(_id("members"));
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

function _id(id) {
    return document.getElementById(id);
}

function _empty(element) {
    while (element.hasChildNodes()) {
        element.removeChild(element.lastChild);
    }
}

function _show(element) {
    const display = element.getAttribute("data-display");
    // Empty string as display restores the default.
    if (display || display === "") {
        element.style.display = display;
    }
}

function _hide(element) {
    element.setAttribute("data-display", element.style.display);
    element.style.display = "none";
}
