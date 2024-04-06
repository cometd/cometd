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
 * A registry for transports used by the CometD object.
 */
export class TransportRegistry {
    #types = [];
    #transports = {};

    getTransportTypes() {
        return this.#types.slice(0);
    }

    add(type, transport, index) {
        let existing = false;
        for (let i = 0; i < this.#types.length; ++i) {
            if (this.#types[i] === type) {
                existing = true;
                break;
            }
        }

        if (!existing) {
            if (typeof index !== "number") {
                this.#types.push(type);
            } else {
                this.#types.splice(index, 0, type);
            }
            this.#transports[type] = transport;
        }

        return !existing;
    }

    find(type) {
        for (let i = 0; i < this.#types.length; ++i) {
            if (this.#types[i] === type) {
                return this.#transports[type];
            }
        }
        return null;
    }

    negotiateTransport(types, version, crossDomain, url) {
        for (let i = 0; i < this.#types.length; ++i) {
            const type = this.#types[i];
            for (let j = 0; j < types.length; ++j) {
                if (type === types[j]) {
                    const transport = this.#transports[type];
                    if (transport.accept(version, crossDomain, url) === true) {
                        return transport;
                    }
                }
            }
        }
        return null;
    }

    clear() {
        this.#types = [];
        this.#transports = {};
    }

    reset(init) {
        for (let i = 0; i < this.#types.length; ++i) {
            this.#transports[this.#types[i]].reset(init);
        }
    }

    findTransportTypes(version, crossDomain, url) {
        const result = [];
        for (let i = 0; i < this.#types.length; ++i) {
            const type = this.#types[i];
            if (this.#transports[type].accept(version, crossDomain, url) === true) {
                result.push(type);
            }
        }
        return result;
    }

    remove(type) {
        for (let i = 0; i < this.#types.length; ++i) {
            if (this.#types[i] === type) {
                this.#types.splice(i, 1);
                const transport = this.#transports[type];
                delete this.#transports[type];
                return transport;
            }
        }
        return null;
    }
}
