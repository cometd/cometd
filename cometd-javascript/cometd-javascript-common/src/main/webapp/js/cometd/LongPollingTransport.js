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

import {RequestTransport} from "./RequestTransport.js";

export class LongPollingTransport extends RequestTransport {
    // By default, support cross domain.
    #supportsCrossDomain = true;

    accept(version, crossDomain, url) {
        return this.#supportsCrossDomain || !crossDomain;
    }

    #newXMLHttpRequest() {
        return new window.XMLHttpRequest();
    }

    #copyContext(xhr) {
        try {
            // Copy external context, to be used in other environments.
            xhr.context = this.context;
        } catch (e) {
            // May happen if XHR is wrapped by Object.seal(),
            // Object.freeze(), or Object.preventExtensions().
            this.debug("Could not copy transport context into XHR", e);
        }
    }

    xhrSend(packet) {
        const xhr = this.#newXMLHttpRequest();
        this.#copyContext(xhr);
        xhr.withCredentials = true;
        xhr.open("POST", packet.url, packet.sync !== true);
        const headers = packet.headers;
        if (headers) {
            for (let headerName in headers) {
                if (headers.hasOwnProperty(headerName)) {
                    xhr.setRequestHeader(headerName, headers[headerName]);
                }
            }
        }
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.onload = () => {
            if (xhr.status === 200) {
                packet.onSuccess(xhr.responseText);
            } else {
                packet.onError(xhr.statusText);
            }
        };
        xhr.onabort = xhr.onerror = () => {
            packet.onError(xhr.statusText);
        };
        xhr.send(packet.body);
        return xhr;
    };

    transportSend(envelope, request) {
        this.debug("Transport", this.type, "sending request", request.id, "envelope", envelope);

        try {
            let sameStack = true;
            request.xhr = this.xhrSend({
                transport: this,
                url: envelope.url,
                sync: envelope.sync,
                headers: this.configuration.requestHeaders,
                body: this.convertToJSON(envelope.messages),
                onSuccess: (response) => {
                    this.debug("Transport", this.type, "received response", response);
                    let success = false;
                    try {
                        const received = this.convertToMessages(response);
                        if (received.length === 0) {
                            this.#supportsCrossDomain = false;
                            this.transportFailure(envelope, request, {
                                httpCode: 204
                            });
                        } else {
                            success = true;
                            this.transportSuccess(envelope, request, received);
                        }
                    } catch (x) {
                        this.debug(x);
                        if (!success) {
                            this.#supportsCrossDomain = false;
                            const failure = {
                                exception: x
                            };
                            failure.httpCode = this.xhrStatus(request.xhr);
                            this.transportFailure(envelope, request, failure);
                        }
                    }
                },
                onError: (reason, exception) => {
                    this.debug("Transport", this.type, "received error", reason, exception);
                    this.#supportsCrossDomain = false;
                    const failure = {
                        reason: reason,
                        exception: exception
                    };
                    failure.httpCode = this.xhrStatus(request.xhr);
                    if (sameStack) {
                        // Keep the semantic of calling callbacks asynchronously.
                        this.setTimeout(() => {
                            this.transportFailure(envelope, request, failure);
                        }, 0);
                    } else {
                        this.transportFailure(envelope, request, failure);
                    }
                }
            });
            sameStack = false;
            return true;
        } catch (x) {
            this.debug("Transport", this.type, "exception:", x);
            this.#supportsCrossDomain = false;
            // Keep the semantic of calling callbacks asynchronously.
            this.setTimeout(() => {
                this.transportFailure(envelope, request, {
                    exception: x
                });
            }, 0);
            return false;
        }
    };

    reset(init) {
        super.reset(init);
        this.#supportsCrossDomain = true;
    };
}
