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

import {CometD} from "../js/cometd/cometd.js";
import {TimeSyncExtension} from "../js/cometd/TimeSyncExtension.js";

window.addEventListener("DOMContentLoaded", () => new Auction());

class Auction {
    #cometd;
    #user;
    #selectedCategory;
    #subscriptions = {};
    #chatSubscription;
    #membersSubscription;

    constructor() {
        // Set up CometD.
        this.#cometd = new CometD();
        const timeSync = new TimeSyncExtension();
        this.#cometd.registerExtension("timeSync", timeSync);
        this.#cometd.timeSync = timeSync;

        // Set up UI.
        const loginInput = _id("login-name");
        loginInput.onkeyup = (e) => {
            if (e.key === "Enter") {
                this.#join();
            }
        };
        _id("login-button").onclick = () => this.#join();

        const searchInput = _id("search-text");
        searchInput.onkeyup = (e) => {
            if (e.key === "Enter") {
                this.#searchItems();
            }
        };
        _id("search-button").onclick = () => this.#searchItems();

        _show(_id("welcome"));
        _hide(_id("auction"));
        _hide(_id("chat"));
        _id("login-name").focus();

        window.onbeforeunload = () => this.#cometd.disconnect();
    }

    #tick(timeSync) {
        const date = timeSync.getServerDate();
        _id("dateTime").innerHTML = date.toUTCString();
        this.#cometd.setTimeout(() => this.#tick(timeSync), 1000);
    }

    #join() {
        const userName = _id("login-name").value;
        if (!userName) {
            alert("Please enter a user name");
            return;
        }

        const url = location.protocol + "//" + location.host + config.contextPath + "/cometd";
        this.#cometd.configure({
            url: url,
            logLevel: "debug"
        });

        this.#cometd.handshake({
            user: userName
        }, (m) => {
            if (m.successful) {
                this.#user = userName;
                this.#tick(this.#cometd.timeSync);
                // A remote call to download the categories.
                // They are constant after the application started.
                this.#cometd.remoteCall("auction/categories", (m) => this.#displayCategories(m.data));
            }
        });

        _hide(_id("welcome"));
        _show(_id("auction"));
        _id("user").innerHTML = userName;
    }

    #searchItems() {
        const searchInput = _id("search-text");
        const searchText = searchInput.value;
        if (!searchText) {
            alert("Please enter a search string");
            return;
        }
        searchInput.value = "";
        this.#cometd.remoteCall("auction/search", searchText, (m) => this.#displayItems(m.data));
    }

    #displayCategories(categories) {
        const container = _id("categories");
        _empty(container);
        for (let i = 0; i < categories.length; ++i) {
            const category = categories[i];
            const row = _el("div");
            row.id = "cat-" + category.id;
            if (category === this.#selectedCategory) {
                row.className = "selected";
            }
            row.onclick = () => {
                if (this.#selectedCategory) {
                    // Reset the currently selected category.
                    _id("cat-" + this.#selectedCategory.id).className = "";
                }
                // Save the selected category.
                this.#selectedCategory = category;
                row.className = "selected";

                // A remote call to download the items for the category.
                this.#cometd.remoteCall("auction/category", category.id, (m) => {
                    this.#displayItems(m.data);
                });
            };
            row.innerHTML = category.name;
            container.appendChild(row);
        }
    }

    #displayItems(items) {
        const div = _id("items");
        _empty(div);

        // For each item, show image, title and description.
        for (let i = 0; i < items.length; ++i) {
            const item = items[i];
            const row = _el("div");

            const img = _el("img");
            img.src = `images/${item.id}.jpg`;
            img.alt = item.description;
            row.appendChild(img);

            const description = _el("div");
            const title = _el("div");
            title.className = "title";
            title.innerHTML = item.name;
            description.appendChild(title);
            const text = _el("div");
            text.innerHTML = item.description;
            description.appendChild(text);
            const button = _el("button");
            button.id = "watch-" + item.id;
            button.innerHTML = "Watch";
            button.onclick = () => this.#watchItem(item);
            description.appendChild(button);
            row.appendChild(description);

            div.appendChild(row);
        }
    }

    #watchItem(item) {
        const list = _id("bids");
        let row = _id("bid-row-" + item.id);
        if (!row) {
            row = _el("tr");
            row.id = "bid-row-" + item.id;

            let cell = _el("td");
            cell.innerHTML = item.name;
            row.appendChild(cell);

            cell = _el("td");
            cell.id = "bid-amount-" + item.id;
            cell.innerHTML = "";
            row.appendChild(cell);

            cell = _el("td");
            cell.id = "bid-user-" + item.id;
            cell.innerHTML = "";
            row.appendChild(cell);

            cell = _el("td");
            const bidButton = _el("button");
            bidButton.id = "bid-button-" + item.id;
            bidButton.innerHTML = "Bid";
            bidButton.onclick = () => this.#editBid(item);
            cell.appendChild(bidButton);
            const bidInput = _el("input");
            bidInput.id = "bid-input-" + item.id;
            bidInput.type = "text";
            bidInput.onkeyup = (e) => {
                if (e.key === "Enter") {
                    this.#confirmBid(item);
                } else if (e.key === "Escape") {
                    this.#cancelBid(item);
                }
            };
            _hide(bidInput);
            cell.appendChild(bidInput);
            row.appendChild(cell);

            cell = _el("td");
            const chatButton = _el("button");
            chatButton.id = "bid-chat-" + item.id;
            chatButton.innerHTML = "Chat";
            chatButton.onclick = () => this.#chatOpen(item);
            cell.appendChild(chatButton);
            row.appendChild(cell);

            cell = _el("td");
            const unwatchButton = _el("button");
            unwatchButton.innerHTML = "Unwatch";
            unwatchButton.onclick = () => this.#unwatchItem(item);
            cell.appendChild(unwatchButton);
            row.appendChild(cell);

            list.appendChild(row);

            _hide(_id("watch-" + item.id));

            // Subscribe to the broadcast channel that updates the bids.
            this.#subscriptions[item.id] = this.#cometd.subscribe("/auction/bid", (m) => {
                this.#updateBid(m.data);
            });
        }
        // Retrieve the current bid to display.
        this.#cometd.remoteCall("auction/bid/current", item.id, (m) => this.#updateBid(m.data));
    }

    #unwatchItem(item) {
        let row = _id("bid-row-" + item.id);
        if (row) {
            row.parentNode.removeChild(row);
        }
        _show(_id("watch-" + item.id));
    }

    #editBid(item) {
        _hide(_id("bid-button-" + item.id));
        const bidInput = _id("bid-input-" + item.id);
        bidInput.value = _id("bid-amount-" + item.id).innerHTML;
        _show(bidInput);
        bidInput.focus();
    }

    #confirmBid(item) {
        const bidInput = _id("bid-input-" + item.id);
        const value = bidInput.value;
        if (!/^\d+(\.\d{1,2})?$/.test(value)) {
            alert("Please enter a valid number");
            return;
        }
        // Send a fire-and-forget bid to the server.
        // The server will broadcast the bid to the cluster,
        // and result in a message to /auction/bid.
        this.#cometd.publish("/service/auction/bid", {
            id: item.id,
            amount: parseFloat(value)
        });
        _hide(_id("bid-input-" + item.id));
        _show(_id("bid-button-" + item.id));
    }

    #cancelBid(item) {
        _hide(_id("bid-input-" + item.id));
        _show(_id("bid-button-" + item.id));
    }

    #updateBid(bid) {
        if (!bid) {
            // No bid yet for this item.
            return;
        }

        const bidAmountCell = _id("bid-amount-" + bid.id);
        const bidUserCell = _id("bid-user-" + bid.id);
        bidAmountCell.innerHTML = bid.amount;
        bidAmountCell.className = bid.bidder === this.#user ? "self" : "other";
        bidUserCell.innerHTML = bid.bidder;
    }

    #chatOpen(item) {
        _hide(_id("bid-chat-" + item.id));
        _show(_id("chat"));
        _id("chat-banner").innerHTML = `<h2>Chat: ${item.name}</h2>`;
        _id("chat-item").innerHTML = `<img src="images/${item.id}.jpg" alt="${item.description}" />`;
        _id("chat-text").onkeyup = (e) => {
            if (e.key === "Enter") {
                this.#chatSend(item);
            }
        };
        _id("chat-send").onclick = () => this.#chatSend(item);
        _id("chat-leave").onclick = () => this.#chatLeave(item);
        this.#cometd.batch(() => {
            const chatChannel = "/auction/chat/" + item.id;
            this.#chatSubscription = this.#cometd.subscribe(chatChannel, (m) => this.#chatMessageDisplay(m.data));
            this.#membersSubscription = this.#cometd.subscribe("/auction/chat/members/" + item.id, (m) => this.#chatMembersDisplay(m.data));
            this.#cometd.publish("/service" + chatChannel, {
                user: this.#user,
                join: true,
                chat: this.#user + " has joined"
            });
        });
    }

    #chatLeave(item) {
        this.#cometd.batch(() => {
            this.#cometd.publish("/service/auction/chat/" + item.id, {
                user: this.#user,
                leave: true,
                chat: this.#user + " has left"
            });
            this.#cometd.unsubscribe(this.#chatSubscription);
        });
        this.#chatSubscription = null;
        _empty(_id("chat-messages"));
        _hide(_id("chat"));
        _show(_id("bid-chat-" + item.id));
    }

    #chatMessageDisplay(data) {
        const area = _id("chat-messages");
        const system = data.hasOwnProperty("join") || data.hasOwnProperty("leave");
        if (system) {
            area.innerHTML += `<div class="system">${data.chat}</div>`;
        } else {
            area.innerHTML += `<div><b>${data.user}:</b> ${data.chat}</div>`;
        }
        area.scrollTop = area.scrollHeight - area.offsetHeight;
    }

    #chatMembersDisplay(data) {
        const members = _id("chat-members");
        _empty(members);
        members.innerHTML = "<div><b>Members</b></div>"
        data.forEach((e) => {
            members.innerHTML += `<div>${e}</div>`;
        });
    }

    #chatSend(item) {
        const chatInput = _id("chat-text");
        const text = chatInput.value;
        chatInput.value = "";
        chatInput.focus();
        if (!text) {
            return;
        }
        const colons = text.indexOf('::');
        const message = {
            user: this.#user,
        };
        if (colons > 0) {
            message.chat = text.substring(colons + 2);
            message.peer = text.substring(0, colons);
        } else {
            message.chat = text;
        }
        // Send it to the server only, the server will then
        // re-broadcast the message on the cluster if it is not private.
        this.#cometd.publish("/service/auction/chat/" + item.id, message);
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

function _el(tag) {
    return document.createElement(tag);
}
