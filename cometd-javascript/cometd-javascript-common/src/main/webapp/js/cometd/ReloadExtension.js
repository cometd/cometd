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
 * The reload extension allows a page to be loaded (or reloaded)
 * without having to re-handshake in the new (or reloaded) page,
 * therefore resuming the existing CometD connection.
 *
 * When the reload() method is called, the state of the CometD
 * connection is stored in the window.sessionStorage object.
 * The reload() method must therefore be called by page unload
 * handlers, often provided by JavaScript toolkits.
 *
 * When the page is (re)loaded, this extension checks the
 * window.sessionStorage and restores the CometD connection,
 * maintaining the same CometD clientId.
 */
export class ReloadExtension extends Extension {
    #state = {};
    #name = "org.cometd.reload";
    #batch = false;
    #reloading = false;

    constructor(configuration) {
        super();
        this.configure(configuration);
    }

    #reload(config) {
        if (this.#state.handshakeResponse) {
            this.#reloading = true;
            const transport = this.cometd.getTransport();
            if (transport) {
                transport.abort();
            }
            this.configure(config);
            const state = JSON.stringify(this.#state);
            this.cometd._debug("Reload extension saving state", state);
            window.sessionStorage.setItem(this.#name, state);
        }
    }

    #similarState(oldState) {
        // We want to check here that the CometD object
        // did not change much between reloads.
        // We just check the URL for now, but in future
        // further checks may involve the transport type
        // and other configuration parameters.
        return this.#state.url === oldState.url;
    }

    configure(config) {
        if (config) {
            if (typeof config.name === "string") {
                this.#name = config.name;
            }
        }
    }

    _receive(response) {
        this.cometd.receive(response);
    }

    registered(name, cometd) {
        super.registered(name, cometd);
        this.cometd.reload = (config) => this.#reload(config);
        this.cometd.addListener("/meta/handshake", (m) => this.#onHandshakeReply(m));
    };

    unregistered() {
        delete this.cometd.reload;
        super.unregistered();
    };

    outgoing(message) {
        switch (message.channel) {
            case "/meta/handshake": {
                this.#state = {
                    url: this.cometd.getURL()
                };

                const state = window.sessionStorage.getItem(this.#name);
                this.cometd._debug("Reload extension found state", state);
                // Is there a saved handshake response from a prior load?
                if (state) {
                    try {
                        const oldState = JSON.parse(state);

                        // Remove the state, not needed anymore.
                        window.sessionStorage.removeItem(this.#name);

                        if (oldState.handshakeResponse && this.#similarState(oldState)) {
                            this.cometd._debug("Reload extension restoring state", oldState);

                            // Since we are going to abort this message,
                            // we must save an eventual callback to restore
                            // it when we replay the handshake response.
                            const callback = this.cometd._getCallback(message.id);

                            this.cometd.setTimeout(() => {
                                this.cometd._debug("Reload extension replaying handshake response", oldState.handshakeResponse);
                                this.#state.handshakeResponse = oldState.handshakeResponse;
                                this.#state.transportType = oldState.transportType;

                                // Restore the callback.
                                this.cometd._putCallback(message.id, callback);

                                const response = this.cometd._mixin(true, {}, this.#state.handshakeResponse, {
                                    // Keep the response message id the same as the request.
                                    id: message.id,
                                    // Tells applications this is a handshake replayed by the reload extension.
                                    ext: {
                                        reload: true
                                    }
                                });
                                // Use the same transport as before.
                                response.supportedConnectionTypes = [this.#state.transportType];

                                this._receive(response);
                                this.cometd._debug("Reload extension replayed handshake response", response);
                            }, 0);

                            // Delay any sends until first connect is complete.
                            // This avoids that there is an old /meta/connect pending on server
                            // that will be resumed to send messages to the client, when the
                            // client has already closed the connection, thereby losing the messages.
                            if (!this.#batch) {
                                this.#batch = true;
                                this.cometd.startBatch();
                            }

                            // This handshake is aborted, as we will replay the prior handshake response.
                            return null;
                        } else {
                            this.cometd._debug("Reload extension could not restore state", oldState);
                        }
                    } catch (x) {
                        this.cometd._debug("Reload extension error while trying to restore state", x);
                    }
                }
                break;
            }
            case "/meta/connect": {
                if (this.#reloading === true) {
                    // The reload causes the failure of the outstanding /meta/connect,
                    // which CometD will react to by sending another. Here we avoid
                    // that /meta/connect messages are sent between the reload and
                    // the destruction of the JavaScript context, so that we are sure
                    // that the first /meta/connect is the one triggered after the
                    // replay of the /meta/handshake by this extension.
                    this.cometd._debug("Reload extension aborting /meta/connect during reload");
                    return null;
                }

                if (!this.#state.transportType) {
                    this.#state.transportType = message.connectionType;
                    this.cometd._debug("Reload extension tracked transport type", this.#state.transportType);
                }
                break;
            }
            case "/meta/disconnect": {
                this.#state = {};
                break;
            }
            default: {
                break;
            }
        }
        return message;
    };

    incoming(message) {
        switch (message.channel) {
            case "/meta/handshake": {
                // Only record the handshake response if it's successful.
                if (message.successful) {
                    // If the handshake response is already present, then we're replaying it.
                    // Since the replay may have modified the handshake response, do not record it again.
                    if (!this.#state.handshakeResponse) {
                        // Save successful handshake response
                        this.#state.handshakeResponse = message;
                        this.cometd._debug("Reload extension tracked handshake response", message);
                    }
                }
                break;
            }
            case "/meta/connect": {
                if (this.#batch) {
                    this.#batch = false;
                    this.cometd.endBatch();
                }
                break;
            }
            case "/meta/disconnect": {
                if (this.#batch) {
                    this.#batch = false;
                    this.cometd.endBatch();
                }
                this.#state = {};
                break;
            }
            default: {
                break;
            }
        }
        return message;
    };

    #onHandshakeReply(message) {
        // Unsuccessful handshakes should
        // not be saved in case of reloads.
        if (message.successful !== true) {
            this.#state = {};
        }
    }
}
