/*
 * Copyright (c) 2008-2018 the original author or authors.
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

(function(root, factory) {
    if (typeof exports === 'object') {
        module.exports = factory(require('jquery'), require('cometd/cometd'));
    } else if (typeof define === 'function' && define.amd) {
        define(['jquery', 'cometd/cometd'], factory);
    } else {
        factory(jQuery, root.org.cometd);
    }
}(this, function($, cometdModule) {
    function _setHeaders(xhr, headers) {
        if (headers) {
            for (var headerName in headers) {
                if (headers.hasOwnProperty(headerName)) {
                    if (headerName.toLowerCase() === 'content-type') {
                        continue;
                    }
                    xhr.setRequestHeader(headerName, headers[headerName]);
                }
            }
        }
    }

    // Remap toolkit-specific transport calls.
    function LongPollingTransport() {
        var _super = new cometdModule.LongPollingTransport();
        var that = cometdModule.Transport.derive(_super);

        that.xhrSend = function(packet) {
            return $.ajax({
                url: packet.url,
                async: packet.sync !== true,
                type: 'POST',
                contentType: 'application/json;charset=UTF-8',
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
        };

        return that;
    }

    function CallbackPollingTransport() {
        var _super = new cometdModule.CallbackPollingTransport();
        var that = cometdModule.Transport.derive(_super);

        that.jsonpSend = function(packet) {
            $.ajax({
                url: packet.url,
                async: packet.sync !== true,
                type: 'GET',
                dataType: 'jsonp',
                jsonp: 'jsonp',
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
        };

        return that;
    }

    $.CometD = function(name) {
        var cometd = new cometdModule.CometD(name);
        cometd.unregisterTransports();
        // Registration order is important.
        if (window.WebSocket) {
            cometd.registerTransport('websocket', new cometdModule.WebSocketTransport());
        }
        cometd.registerTransport('long-polling', new LongPollingTransport());
        cometd.registerTransport('callback-polling', new CallbackPollingTransport());

        return cometd;
    };

    // The default cometd instance.
    $.cometd = new $.CometD();
    return $.cometd;
}));
