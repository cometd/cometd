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

import {Extension} from "./Extension.js";

/**
 * This client-side extension enables the client to acknowledge to the server
 * the messages that the client has received.
 * For the acknowledgement to work, the server must be configured with the
 * correspondent server-side ack extension. If both client and server support
 * the ack extension, then the ack functionality will take place automatically.
 * By enabling this extension, all messages arriving from the server will arrive
 * via /meta/connect, so the comet communication will be slightly chattier.
 * The fact that all messages will return via /meta/connect means also that the
 * messages will arrive with total order, which is not guaranteed if messages
 * can arrive via both /meta/connect and normal response.
 * Messages are not acknowledged one by one, but instead a batch of messages is
 * acknowledged when the /meta/connect returns.
 */
export class AckExtension extends Extension {
    #serverSupportsAcks = false;
    #batch = 0;

    #debug(text, args) {
        this.cometd._debug(text, args);
    }

    registered(name, cometd) {
        super.registered(name, cometd);
        this.#debug("AckExtension: executing registration callback");
    };

    unregistered() {
        this.#debug("AckExtension: executing unregistration callback");
        super.unregistered();
    };

    incoming(message) {
        const channel = message.channel;
        const ext = message.ext;
        if (channel === "/meta/handshake") {
            if (ext) {
                const ackField = ext.ack;
                if (typeof ackField === "object") {
                    // New format.
                    this.#serverSupportsAcks = ackField.enabled === true;
                    const batch = ackField.batch;
                    if (typeof batch === "number") {
                        this.#batch = batch;
                    }
                } else {
                    // Old format.
                    this.#serverSupportsAcks = ackField === true;
                }
            }
            this.#debug("AckExtension: server supports acknowledgements", this.#serverSupportsAcks);
        } else if (channel === "/meta/connect" && message.successful && this.#serverSupportsAcks) {
            if (ext && typeof ext.ack === "number") {
                this.#batch = ext.ack;
                this.#debug("AckExtension: server sent batch", this.#batch);
            }
        }
        return message;
    };

    outgoing(message) {
        const channel = message.channel;
        if (!message.ext) {
            message.ext = {};
        }
        if (channel === "/meta/handshake") {
            message.ext.ack = this.cometd && this.cometd.ackEnabled !== false;
            this.#serverSupportsAcks = false;
            this.#batch = 0;
        } else if (channel === "/meta/connect") {
            if (this.#serverSupportsAcks) {
                message.ext.ack = this.#batch;
                this.#debug("AckExtension: client sending batch", this.#batch);
            }
        }
        return message;
    };
}
