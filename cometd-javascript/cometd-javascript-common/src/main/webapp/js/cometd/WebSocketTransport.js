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

import {Transport} from "./Transport.js";

export class WebSocketTransport extends Transport {
    // By default, WebSocket is supported.
    #webSocketSupported = true;
    // Whether we were able to establish a WebSocket connection.
    #webSocketConnected = false;
    #stickyReconnect = true;
    // The context contains the envelopes that have been sent
    // and the timeouts for the messages that have been sent.
    #context = null;
    #connecting = null;
    #connected = false;
    #successCallback = null;

    reset(init) {
        super.reset(init);
        this.#webSocketSupported = true;
        if (init) {
            this.#webSocketConnected = false;
        }
        this.#stickyReconnect = true;
        if (init) {
            this.#context = null;
        }
        this.#connecting = null;
        this.#connected = false;
    };

    #forceClose(context, event) {
        if (context) {
            this.#webSocketClose(context, event.code, event.reason);
            // Force immediate failure of pending messages to trigger reconnect.
            // This is needed because the server may not reply to our close()
            // and therefore the onclose function is never called.
            this.#onClose(context, event);
        }
    }

    #sameContext(context) {
        return context === this.#connecting || context === this.#context;
    }

    #storeEnvelope(context, envelope, metaConnect) {
        const messageIds = [];
        for (let i = 0; i < envelope.messages.length; ++i) {
            const message = envelope.messages[i];
            if (message.id) {
                messageIds.push(message.id);
            }
        }
        context.envelopes[messageIds.join(",")] = [envelope, metaConnect];
        this.debug("Transport", this.type, "stored envelope, envelopes", context.envelopes);
    }

    #removeEnvelope(context, messageIds) {
        let removed = false;
        const envelopes = context.envelopes;
        for (let j = 0; j < messageIds.length; ++j) {
            const id = messageIds[j];
            for (let key in envelopes) {
                if (envelopes.hasOwnProperty(key)) {
                    const ids = key.split(",");
                    const index = ids.indexOf(id);
                    if (index >= 0) {
                        removed = true;
                        ids.splice(index, 1);
                        const envelope = envelopes[key][0];
                        const metaConnect = envelopes[key][1];
                        delete envelopes[key];
                        if (ids.length > 0) {
                            envelopes[ids.join(",")] = [envelope, metaConnect];
                        }
                        break;
                    }
                }
            }
        }
        if (removed) {
            this.debug("Transport", this.type, "removed envelope, envelopes", envelopes);
        }
    }

    #websocketConnect(context) {
        // We may have multiple attempts to open a WebSocket
        // connection, for example a /meta/connect request that
        // may take time, along with a user-triggered publish.
        // Early return if we are already connecting.
        if (this.#connecting) {
            return;
        }

        // Mangle the URL, changing the scheme from "http" to "ws".
        const url = this.cometd.getURL().replace(/^http/, "ws");
        this.debug("Transport", this.type, "connecting to URL", url);

        try {
            const protocol = this.configuration.protocol;
            context.webSocket = protocol ? new window.WebSocket(url, protocol) : new window.WebSocket(url);
            this.#connecting = context;
        } catch (x) {
            this.#webSocketSupported = false;
            this.debug("Exception while creating WebSocket object", x);
            throw x;
        }

        // By default use sticky reconnects.
        this.#stickyReconnect = this.configuration.stickyReconnect !== false;

        const connectTimeout = this.configuration.connectTimeout;
        if (connectTimeout > 0) {
            context.connectTimer = this.setTimeout(() => {
                this.debug("Transport", this.type, "timed out while connecting to URL", url, ":", connectTimeout, "ms");
                // The connection was not opened, close anyway.
                this.#forceClose(context, {
                    code: 1000,
                    reason: "Connect Timeout"
                });
            }, connectTimeout);
        }

        const onopen = () => {
            this.debug("Transport", this.type, "onopen", context);
            if (context.connectTimer) {
                this.clearTimeout(context.connectTimer);
            }

            if (this.#sameContext(context)) {
                this.#connecting = null;
                this.#context = context;
                this.#webSocketConnected = true;
                this.#onOpen(context);
            } else {
                // We have a valid connection already, close this one.
                this.cometd._warn("Closing extra WebSocket connection", this, "active connection", this.#context);
                this.#forceClose(context, {
                    code: 1000,
                    reason: "Extra Connection"
                });
            }
        };

        // This callback is invoked when the server sends the close frame.
        // The close frame for a connection may arrive *after* another
        // connection has been opened, so we must make sure that actions
        // are performed only if it's the same connection.
        const onclose = (event) => {
            event = event || {code: 1000};
            this.debug("Transport", this.type, "onclose", context, event, "connecting", this.#connecting, "current", this.#context);

            if (context.connectTimer) {
                this.clearTimeout(context.connectTimer);
            }

            this.#onClose(context, event);
        };

        const onmessage = (wsMessage) => {
            this.debug("Transport", this.type, "onmessage", wsMessage, context);
            this.#onMessage(context, wsMessage);
        };

        context.webSocket.onopen = onopen;
        context.webSocket.onclose = onclose;
        context.webSocket.onerror = () => {
            // Clients should call onclose(), but if they do not we do it here for safety.
            onclose({
                code: 1000,
                reason: "Error"
            });
        };
        context.webSocket.onmessage = onmessage;

        this.debug("Transport", this.type, "configured callbacks on", context);
    }

    #onTransportTimeout(context, message, delay) {
        const result = this.notifyTransportTimeout([message]);
        if (result > 0) {
            this.debug("Transport", this.type, "extended waiting for message replies:", result, "ms");
            context.timeouts[message.id] = this.setTimeout(() => {
                this.#onTransportTimeout(context, message, delay + result);
            }, result);
        } else {
            this.debug("Transport", this.type, "expired waiting for message reply", message.id, ":", delay, "ms");
            this.#forceClose(context, {
                code: 1000,
                reason: "Message Timeout"
            });
        }
    }

    #webSocketSend(context, envelope, metaConnect) {
        let json;
        try {
            json = this.convertToJSON(envelope.messages);
        } catch (x) {
            this.debug("Transport", this.type, "exception:", x);
            const mIds = [];
            for (let j = 0; j < envelope.messages.length; ++j) {
                const m = envelope.messages[j];
                mIds.push(m.id);
            }
            this.#removeEnvelope(context, mIds);
            // Keep the semantic of calling callbacks asynchronously.
            this.setTimeout(() => {
                this._notifyFailure(envelope.onFailure, context, envelope.messages, {
                    exception: x
                });
            }, 0);
            return;
        }

        context.webSocket.send(json);
        this.debug("Transport", this.type, "sent", envelope, "/meta/connect =", metaConnect);

        // Manage the timeout waiting for the response.
        let delay = this.configuration.maxNetworkDelay;
        if (metaConnect) {
            delay += this.advice.timeout;
            this.#connected = true;
        }

        const messageIds = [];
        for (let i = 0; i < envelope.messages.length; ++i) {
            const message = envelope.messages[i];
            if (message.id) {
                messageIds.push(message.id);
                context.timeouts[message.id] = this.setTimeout(() => {
                    this.#onTransportTimeout(context, message, delay);
                }, delay);
            }
        }

        this.debug("Transport", this.type, "started waiting for message replies", delay, "ms, messageIds:", messageIds, ", timeouts:", context.timeouts);
    }

    _notifySuccess(fn, messages) {
        fn.call(this, messages);
    };

    _notifyFailure(fn, context, messages, failure) {
        fn.call(this, context, messages, failure);
    };

    #send(context, envelope, metaConnect) {
        try {
            if (context === null) {
                context = this.#connecting || {
                    envelopes: {},
                    timeouts: {}
                };
                this.#storeEnvelope(context, envelope, metaConnect);
                this.#websocketConnect(context);
            } else {
                this.#storeEnvelope(context, envelope, metaConnect);
                this.#webSocketSend(context, envelope, metaConnect);
            }
        } catch (x) {
            // Keep the semantic of calling callbacks asynchronously.
            this.setTimeout(() => {
                this.#forceClose(context, {
                    code: 1000,
                    reason: "Exception",
                    exception: x
                });
            }, 0);
        }
    }

    #onOpen(context) {
        const envelopes = context.envelopes;
        this.debug("Transport", this.type, "opened", context, "pending messages", envelopes);
        for (let key in envelopes) {
            if (envelopes.hasOwnProperty(key)) {
                const element = envelopes[key];
                const envelope = element[0];
                const metaConnect = element[1];
                // Store the success callback, which is independent of the envelope,
                // so that it can be used to notify arrival of messages.
                this.#successCallback = envelope.onSuccess;
                this.#webSocketSend(context, envelope, metaConnect);
            }
        }
    };

    #onMessage(context, wsMessage) {
        this.debug("Transport", this.type, "received websocket message", wsMessage, context);

        let close = false;
        const messages = this.convertToMessages(wsMessage.data);
        const messageIds = [];
        for (let i = 0; i < messages.length; ++i) {
            const message = messages[i];

            // Detect if the message is a response to a request we made.
            // If it's a meta message, for sure it's a response; otherwise it's
            // a publish message and publish responses don't have the data field.
            if (/^\/meta\//.test(message.channel) || message.data === undefined) {
                if (message.id) {
                    messageIds.push(message.id);

                    const timeout = context.timeouts[message.id];
                    if (timeout) {
                        this.clearTimeout(timeout);
                        delete context.timeouts[message.id];
                        this.debug("Transport", this.type, "removed timeout for message", message.id, ", timeouts", context.timeouts);
                    }
                }
            }

            if ("/meta/connect" === message.channel) {
                this.#connected = false;
            }
            if ("/meta/disconnect" === message.channel && !this.#connected) {
                close = true;
            }
        }

        // Remove the envelope corresponding to the messages.
        this.#removeEnvelope(context, messageIds);

        this._notifySuccess(this.#successCallback, messages);

        if (close) {
            this.#webSocketClose(context, 1000, "Disconnect");
        }
    };

    #onClose(context, event) {
        this.debug("Transport", this.type, "closed", context, event);

        if (this.#sameContext(context)) {
            // Remember if we were able to connect.
            // This close event could be due to server shutdown,
            // and if it restarts we want to try websocket again.
            this.#webSocketSupported = this.#stickyReconnect && this.#webSocketConnected;
            this.#connecting = null;
            this.#context = null;
        }

        const timeouts = context.timeouts;
        context.timeouts = {};
        for (let id in timeouts) {
            if (timeouts.hasOwnProperty(id)) {
                this.clearTimeout(timeouts[id]);
            }
        }

        const envelopes = context.envelopes;
        context.envelopes = {};
        for (let key in envelopes) {
            if (envelopes.hasOwnProperty(key)) {
                const envelope = envelopes[key][0];
                const metaConnect = envelopes[key][1];
                if (metaConnect) {
                    this.#connected = false;
                }
                const failure = {
                    websocketCode: event.code,
                    reason: event.reason
                };
                if (event.exception) {
                    failure.exception = event.exception;
                }
                this._notifyFailure(envelope.onFailure, context, envelope.messages, failure);
            }
        }
    };

    accept(version, crossDomain, url) {
        this.debug("Transport", this.type, "accept, supported:", this.#webSocketSupported);
        // Using !! to return a boolean (and not the WebSocket object).
        return this.#webSocketSupported && !!window.WebSocket && this.cometd.websocketEnabled !== false;
    };

    send(envelope, metaConnect) {
        this.debug("Transport", this.type, "sending", envelope, "/meta/connect =", metaConnect);
        this.#send(this.#context, envelope, metaConnect);
    };

    #webSocketClose(context, code, reason) {
        try {
            if (context.webSocket) {
                context.webSocket.close(code, reason);
            }
        } catch (x) {
            this.debug(x);
        }
    };

    abort() {
        super.abort();
        this.#forceClose(this.#context, {
            code: 1000,
            reason: "Abort"
        });
        this.reset(true);
    };
}
