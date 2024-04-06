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
 * Base class for CometD extensions.
 */
export class Extension {
    #cometd;

    registered(name, cometd) {
        this.#cometd = cometd;
    }

    unregistered() {
        this.#cometd = null;
    }

    get cometd() {
        return this.#cometd;
    }

    incoming(message) {
        return message;
    }

    outgoing(message) {
        return message;
    }
}
