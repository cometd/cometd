/*
 * Copyright (c) 2008-2020 the original author or authors.
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

define(['cometd/cometd', 'dojo/json', 'dojox', 'dojo/_base/xhr', 'dojo/request/script'],
    function(cometdModule, JSON, dojox, dojoXHR, dojoSCRIPT) {
        function LongPollingTransport() {
            var _super = new cometdModule.LongPollingTransport();
            var that = cometdModule.Transport.derive(_super);

            that.xhrSend = function(packet) {
                var deferred = dojoXHR.post({
                    url: packet.url,
                    sync: packet.sync === true,
                    contentType: 'application/json;charset=UTF-8',
                    headers: packet.headers,
                    postData: packet.body,
                    withCredentials: true,
                    handleAs: 'json',
                    load: packet.onSuccess,
                    error: function(error) {
                        packet.onError(error.message, deferred ? deferred.ioArgs.error : error);
                    }
                });
                return deferred.ioArgs.xhr;
            };

            return that;
        }

        function CallbackPollingTransport() {
            var _super = new cometdModule.CallbackPollingTransport();
            var that = cometdModule.Transport.derive(_super);

            that.jsonpSend = function(packet) {
                dojoSCRIPT.get(packet.url, {
                    jsonp: 'jsonp',
                    query: {
                        // In callback-polling, the content must be sent via the 'message' parameter
                        message: packet.body
                    },
                    sync: packet.sync === true
                }).then(packet.onSuccess, function(error) {
                    // Actually never called by Dojo, perhaps a Dojo bug.
                    packet.onError(error);
                });
                return undefined;
            };

            return that;
        }

        dojox.CometD = function(name) {
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

        // The default cometd instance
        var cometd = new dojox.CometD();
        dojox.cometd = cometd;
        return cometd;
    });
