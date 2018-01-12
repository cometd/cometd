/*
 * Copyright (c) 2008-2017 the original author or authors.
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
angular.module('cometd', [])
    .factory('cometd', ['$rootScope', '$http', function($rootScope, $http) {
        function LongPollingTransport() {
            var _super = new org.cometd.LongPollingTransport();
            var that = org.cometd.Transport.derive(_super);

            that.xhrSend = function(packet) {
                var xhr = {};

                var hdrs = packet.headers || {};
                hdrs['Content-Type'] = 'application/json;charset=UTF-8';

                $http.post(packet.url, packet.body, {
                    headers: hdrs,
                    withCredentials: true
                }).then(function(response) {
                    xhr.status = response.status;
                    packet.onSuccess(response.data);
                }, function(response) {
                    xhr.status = response.status;
                    packet.onError(response.statusText);
                });

                return xhr;
            };

            return that;
        }

        function CallbackPollingTransport() {
            var _super = new org.cometd.CallbackPollingTransport();
            var that = org.cometd.Transport.derive(_super);

            that.jsonpSend = function(packet) {
                $http.jsonp(packet.url, {
                    headers: packet.headers,
                    jsonpCallbackParam: 'jsonp',
                    params: {
                        // In callback-polling, the content must be sent via the 'message' parameter.
                        message: packet.body
                    }
                }).then(function(response) {
                    packet.onSuccess(response.data);
                }, function(response) {
                    packet.onError(response.statusText);
                });
            };

            return that;
        }

        function _angularize(fn) {
            return function() {
                var self = this;
                var args = arguments;
                return $rootScope.$apply(function() {
                    return fn.apply(self, args);
                });
            }
        }

        $rootScope.CometD = function(name) {
            var cometd = new org.cometd.CometD(name);
            cometd.unregisterTransports();
            // Registration order is important.
            if (window.WebSocket) {
                var ws = new org.cometd.WebSocketTransport();
                ws._notifySuccess = _angularize(ws._notifySuccess);
                ws._notifyFailure = _angularize(ws._notifyFailure);
                cometd.registerTransport('websocket', ws);
            }
            cometd.registerTransport('long-polling', new LongPollingTransport());
            cometd.registerTransport('callback-polling', new CallbackPollingTransport());

            return cometd;
        };

        return new $rootScope.CometD();
    }]);
