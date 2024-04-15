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

import "jquery";
import {CometD} from "cometd/cometd.js";
import {LongPollingTransport} from "cometd/LongPollingTransport.js";
import {CallbackPollingTransport} from "cometd/CallbackPollingTransport.js";
import {WebSocketTransport} from "cometd/WebSocketTransport.js";

function _setHeaders(xhr, headers) {
    if (headers) {
        for (const headerName in headers) {
            if (headers.hasOwnProperty(headerName)) {
                if (headerName.toLowerCase() === "content-type") {
                    continue;
                }
                xhr.setRequestHeader(headerName, headers[headerName]);
            }
        }
    }
}

class jqLongPollingTransport extends LongPollingTransport {
    xhrSend(packet) {
        return $.ajax({
            url: packet.url,
            async: packet.sync !== true,
            type: "POST",
            contentType: "application/json",
            data: packet.body,
            global: false,
            xhrFields: {
                // For asynchronous calls.
                withCredentials: true
            },
            beforeSend: function(xhr) {
                // For synchronous calls.
                xhr.withCredentials = true;
                _setHeaders(xhr, packet.headers);
                // Returning false will abort the XHR send.
                return true;
            },
            success: packet.onSuccess,
            error: function(xhr, reason, exception) {
                packet.onError(reason, exception);
            }
        });
    }
}

class jqCallbackPollingTransport extends CallbackPollingTransport {
    jsonpSend(packet) {
        $.ajax({
            url: packet.url,
            async: packet.sync !== true,
            type: "GET",
            dataType: "jsonp",
            jsonp: "jsonp",
            data: {
                // In callback-polling, the content must be sent via the 'message' parameter.
                message: packet.body
            },
            beforeSend: function(xhr) {
                _setHeaders(xhr, packet.headers);
                // Returning false will abort the XHR send.
                return true;
            },
            success: packet.onSuccess,
            error: function(xhr, reason, exception) {
                packet.onError(reason, exception);
            }
        });
    }
}

export const cometd = new CometD();
cometd.unregisterTransports();
// Registration order is important.
if (window.WebSocket) {
    cometd.registerTransport("websocket", new WebSocketTransport());
}
cometd.registerTransport("long-polling", new jqLongPollingTransport());
cometd.registerTransport("callback-polling", new jqCallbackPollingTransport());

$.cometd = cometd;
