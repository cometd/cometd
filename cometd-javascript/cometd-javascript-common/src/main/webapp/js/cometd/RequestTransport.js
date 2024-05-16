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

/**
 * Base class with the common functionality for transports based on requests.
 * The key responsibility is to allow at most 2 outstanding requests to the server,
 * to avoid that requests are sent behind a long poll.
 * To achieve this, we have one reserved request for the long poll, and all other
 * requests are serialized one after the other.
 */
export class RequestTransport extends Transport {
    #requestIds = 0;
    #metaConnectRequest = null;
    #requests = [];
    #envelopes = [];

    #coalesceEnvelopes(envelope) {
        while (this.#envelopes.length > 0) {
            const envelopeAndRequest = this.#envelopes[0];
            const newEnvelope = envelopeAndRequest[0];
            const newRequest = envelopeAndRequest[1];
            if (newEnvelope.url === envelope.url &&
                newEnvelope.sync === envelope.sync) {
                this.#envelopes.shift();
                envelope.messages = envelope.messages.concat(newEnvelope.messages);
                this.debug("Coalesced", newEnvelope.messages.length, "messages from request", newRequest.id);
                continue;
            }
            break;
        }
    }

    #onTransportTimeout(envelope, request, delay) {
        const result = this.notifyTransportTimeout(envelope.messages);
        if (result > 0) {
            this.debug("Transport", this.type, "extended waiting for message replies of request", request.id, ":", result, "ms");
            request.timeout = this.setTimeout(() => {
                this.#onTransportTimeout(envelope, request, delay + result);
            }, result);
        } else {
            request.expired = true;
            const errorMessage = "Transport " + this + " expired waiting for message replies of request " + request.id + ": " + delay + " ms";
            const failure = {
                reason: errorMessage
            };
            const xhr = request.xhr;
            failure.httpCode = this.xhrStatus(xhr);
            this.abortXHR(xhr);
            this.debug(errorMessage);
            this.complete(request, false, request.metaConnect);
            envelope.onFailure(xhr, envelope.messages, failure);
        }
    }

    #transportSend(envelope, request) {
        if (this.transportSend(envelope, request)) {
            request.expired = false;

            if (!envelope.sync) {
                let delay = this.configuration.maxNetworkDelay;
                if (request.metaConnect === true) {
                    delay += this.advice.timeout;
                }

                this.debug("Transport", this.type, "started waiting for message replies of request", request.id, ":", delay, "ms");

                request.timeout = this.setTimeout(() => {
                    this.#onTransportTimeout(envelope, request, delay);
                }, delay);
            }
        }
    }

    #queueSend(envelope) {
        const requestId = ++this.#requestIds;
        const request = {
            id: requestId,
            metaConnect: false,
            envelope: envelope
        };

        // Consider the /meta/connect requests which should always be present.
        if (this.#requests.length < this.configuration.maxConnections - 1) {
            this.#requests.push(request);
            this.#transportSend(envelope, request);
        } else {
            this.debug("Transport", this.type, "queueing request", requestId, "envelope", envelope);
            this.#envelopes.push([envelope, request]);
        }
    }

    #metaConnectComplete(request) {
        const requestId = request.id;
        this.debug("Transport", this.type, "/meta/connect complete, request", requestId);
        if (this.#metaConnectRequest !== null && this.#metaConnectRequest.id !== requestId) {
            throw new Error("/meta/connect request mismatch, completing request " + requestId);
        }
        this.#metaConnectRequest = null;
    }

    #complete(request, success) {
        const index = this.#requests.indexOf(request);
        // The index can be negative if the request has been aborted
        if (index >= 0) {
            this.#requests.splice(index, 1);
        }

        if (this.#envelopes.length > 0) {
            const envelopeAndRequest = this.#envelopes.shift();
            const nextEnvelope = envelopeAndRequest[0];
            const nextRequest = envelopeAndRequest[1];
            this.debug("Transport dequeued request", nextRequest.id);
            if (success) {
                if (this.configuration.autoBatch) {
                    this.#coalesceEnvelopes(nextEnvelope);
                }
                this.#queueSend(nextEnvelope);
                this.debug("Transport", this.type, "completed request", request.id, nextEnvelope);
            } else {
                // Keep the semantic of calling callbacks asynchronously.
                this.setTimeout(() => {
                    this.complete(nextRequest, false, nextRequest.metaConnect);
                    const failure = {
                        reason: "Previous request failed"
                    };
                    const xhr = nextRequest.xhr;
                    failure.httpCode = this.xhrStatus(xhr);
                    nextEnvelope.onFailure(xhr, nextEnvelope.messages, failure);
                }, 0);
            }
        }
    }

    complete(request, success, metaConnect) {
        if (metaConnect) {
            this.#metaConnectComplete(request);
        } else {
            this.#complete(request, success);
        }
    };

    /**
     * Performs the actual send depending on the transport type details.
     * @param envelope the envelope to send
     * @param request the request information
     * @return {boolean} whether the send succeeded
     */
    transportSend(envelope, request) {
        throw new Error("Abstract");
    };

    transportSuccess(envelope, request, responses) {
        if (!request.expired) {
            this.clearTimeout(request.timeout);
            this.debug("Transport", this.type, "cancelled waiting for message replies");
            this.complete(request, true, request.metaConnect);
            if (responses && responses.length > 0) {
                envelope.onSuccess(responses);
            } else {
                envelope.onFailure(request.xhr, envelope.messages, {
                    httpCode: 204
                });
            }
        }
    };

    transportFailure(envelope, request, failure) {
        if (!request.expired) {
            this.clearTimeout(request.timeout);
            this.debug("Transport", this.type, "cancelled waiting for failed message replies");
            this.complete(request, false, request.metaConnect);
            envelope.onFailure(request.xhr, envelope.messages, failure);
        }
    };

    #metaConnectSend(envelope) {
        if (this.#metaConnectRequest !== null) {
            throw new Error("Concurrent /meta/connect requests not allowed, request id=" + this.#metaConnectRequest.id + " not yet completed");
        }

        const requestId = ++this.#requestIds;
        this.debug("Transport", this.type, "/meta/connect send, request", requestId, "envelope", envelope);
        const request = {
            id: requestId,
            metaConnect: true,
            envelope: envelope
        };
        this.#transportSend(envelope, request);
        this.#metaConnectRequest = request;
    }

    send(envelope, metaConnect) {
        if (metaConnect) {
            this.#metaConnectSend(envelope);
        } else {
            this.#queueSend(envelope);
        }
    };

    abort() {
        super.abort();
        for (let i = 0; i < this.#requests.length; ++i) {
            const request = this.#requests[i];
            if (request) {
                this.debug("Aborting request", request);
                if (!this.abortXHR(request.xhr)) {
                    this.transportFailure(request.envelope, request, {
                        reason: "abort"
                    });
                }
            }
        }
        const metaConnectRequest = this.#metaConnectRequest;
        if (metaConnectRequest) {
            this.debug("Aborting /meta/connect request", metaConnectRequest);
            if (!this.abortXHR(metaConnectRequest.xhr)) {
                this.transportFailure(metaConnectRequest.envelope, metaConnectRequest, {
                    reason: "abort"
                });
            }
        }
        this.reset(true);
    };

    reset(init) {
        super.reset(init);
        this.#metaConnectRequest = null;
        this.#requests = [];
        this.#envelopes = [];
    };

    abortXHR(xhr) {
        if (xhr) {
            try {
                const state = xhr.readyState;
                xhr.abort();
                return state !== window.XMLHttpRequest.UNSENT;
            } catch (x) {
                this.debug(x);
            }
        }
        return false;
    };

    xhrStatus(xhr) {
        if (xhr) {
            try {
                return xhr.status;
            } catch (x) {
                this.debug(x);
            }
        }
        return -1;
    };
}
