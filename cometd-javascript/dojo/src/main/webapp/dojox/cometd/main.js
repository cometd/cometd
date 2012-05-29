/*
 * Copyright (c) 2010 the original author or authors.
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

define(['org/cometd', 'dojo/json', 'dojox', 'dojo/_base/xhr', 'dojo/io/script', 'dojo/topic'],
        function(org_cometd, JSON, dojox, dojoXHR, dojoSCRIPT, topic)
{
    // Remap cometd JSON functions to dojo JSON functions
    org_cometd.JSON.toJSON = JSON.stringify;
    org_cometd.JSON.fromJSON = JSON.parse;

    dojox.Cometd = function(name)
    {
        var cometd = new org_cometd.Cometd(name);

        function LongPollingTransport()
        {
            var _super = new org_cometd.LongPollingTransport();
            var that = org_cometd.Transport.derive(_super);

            that.xhrSend = function(packet)
            {
                var deferred = dojoXHR.post({
                    url: packet.url,
                    sync: packet.sync === true,
                    contentType: 'application/json;charset=UTF-8',
                    headers: packet.headers,
                    postData: packet.body,
                    withCredentials: true,
                    handleAs: 'json',
                    load: packet.onSuccess,
                    error: function(error)
                    {
                        packet.onError(error.message, deferred ? deferred.ioArgs.error : error);
                    }
                });
                return deferred.ioArgs.xhr;
            };

            return that;
        }

        function CallbackPollingTransport()
        {
            var _super = new org_cometd.CallbackPollingTransport();
            var that = org_cometd.Transport.derive(_super);

            that.jsonpSend = function(packet)
            {
                var deferred = dojoSCRIPT.get({
                    url: packet.url,
                    sync: packet.sync === true,
                    callbackParamName: 'jsonp',
                    content: {
                        // In callback-polling, the content must be sent via the 'message' parameter
                        message: packet.body
                    },
                    load: packet.onSuccess,
                    error: function(error)
                    {
                        packet.onError(error.message, deferred ? deferred.ioArgs.error : error);
                    }
                });
                return undefined;
            };

            return that;
        }

        // Registration order is important
        if (org_cometd.WebSocket)
        {
            cometd.registerTransport('websocket', new org_cometd.WebSocketTransport());
        }
        cometd.registerTransport('long-polling', new LongPollingTransport());
        cometd.registerTransport('callback-polling', new CallbackPollingTransport());

        return cometd;
    };

    // The default cometd instance
    var cometd = new dojox.Cometd();
    dojox.cometd = cometd;

    // Create a compatibility API for dojox.cometd instance with the original API.
    cometd._init = cometd.init;
    cometd._unsubscribe = cometd.unsubscribe;
    cometd.unsubscribe = function(channelOrToken, objOrFunc, funcName)
    {
        if (typeof channelOrToken === 'string')
        {
            throw 'Deprecated function unsubscribe(string). Use unsubscribe(object) passing as argument the return value of subscribe()';
        }
        cometd._unsubscribe(channelOrToken);
    };
    cometd._metaHandshakeEvent = function(event)
    {
        event.action = "handshake";
        topic.publish("/cometd/meta", [event]);
    };
    cometd._metaConnectEvent = function(event)
    {
        event.action = "connect";
        topic.publish("/cometd/meta", [event]);
    };
    cometd.addListener('/meta/handshake', cometd, cometd._metaHandshakeEvent);
    cometd.addListener('/meta/connect', cometd, cometd._metaConnectEvent);

    return cometd;
});
