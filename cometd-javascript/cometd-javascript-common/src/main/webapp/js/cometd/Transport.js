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

/**
 * Base class with the common functionality for transports.
 */
export class Transport {
    #type;
    #cometd;
    #url;

    /**
     * Function invoked just after a transport has been successfully registered.
     * @param type the type of transport (for example "long-polling")
     * @param cometd the cometd object this transport has been registered to
     * @see #unregistered()
     */
    registered(type, cometd) {
        this.#type = type;
        this.#cometd = cometd;
    };

    /**
     * Function invoked just after a transport has been successfully unregistered.
     * @see #registered(type, cometd)
     */
    unregistered() {
        this.#type = null;
        this.#cometd = null;
    };

    get cometd() {
        return this.#cometd;
    }

    notifyTransportTimeout(messages) {
        const callbacks = this.cometd._getTransportListeners("timeout");
        if (callbacks) {
            for (let i = 0; i < callbacks.length; ++i) {
                const listener = callbacks[i];
                try {
                    const result = listener.call(this, messages);
                    if (typeof result === "number" && result > 0) {
                        return result;
                    }
                } catch (x) {
                    this.cometd._info("Exception during execution of transport listener", listener, x);
                }
            }
        }
        return 0;
    };

    debug() {
        this.cometd._debug.apply(this.cometd, arguments);
    };

    get configuration() {
        return this.cometd.getConfiguration();
    }

    get advice() {
        return this.cometd.getAdvice();
    }

    setTimeout(funktion, delay) {
        return this.cometd.setTimeout(funktion, delay);
    }

    clearTimeout(id) {
        this.cometd.clearTimeout(id);
    };

    convertToJSON(messages) {
        const maxSize = this.configuration.maxSendBayeuxMessageSize;
        let result = "[";
        for (let i = 0; i < messages.length; ++i) {
            if (i > 0) {
                result += ",";
            }
            const message = messages[i];
            const json = JSON.stringify(message);
            if (json.length > maxSize) {
                throw new Error("maxSendBayeuxMessageSize " + maxSize + " exceeded");
            }
            result += json;
        }
        result += "]";
        return result;
    };

    /**
     * Converts the given response into an array of bayeux messages
     * @param response the response to convert
     * @return an array of bayeux messages obtained by converting the response
     */
    convertToMessages(response) {
        if (response === undefined || response === null) {
            return [];
        }
        if (typeof response === "string" || response instanceof String) {
            try {
                return JSON.parse(response);
            } catch (x) {
                this.debug("Could not convert to JSON the following string:", response);
                throw x;
            }
        }
        if (Array.isArray(response)) {
            return response;
        }
        if (response instanceof Object) {
            return [response];
        }
        throw new Error("Conversion Error " + response + ", typeof " + typeof response);
    };

    /**
     * Returns whether this transport can work for the given version and cross domain communication case.
     * @param version a string indicating the transport version
     * @param crossDomain a boolean indicating whether the communication is cross domain
     * @param url the URL to connect to
     * @return true if this transport can work for the given version and cross domain communication case,
     * false otherwise
     */
    accept(version, crossDomain, url) {
        throw new Error("Abstract");
    };

    /**
     * Returns the type of this transport.
     * @see #registered(type, cometd)
     */
    get type() {
        return this.#type;
    }

    get url() {
        return this.#url;
    }

    set url(url) {
        this.#url = url;
    };

    send(envelope, metaConnect) {
        throw new Error("Abstract");
    };

    reset(init) {
        this.debug("Transport", this.type, "reset", init ? "initial" : "retry");
    };

    abort() {
        this.debug("Transport", this.type, "aborted");
    };

    toString() {
        return this.type;
    };
}
