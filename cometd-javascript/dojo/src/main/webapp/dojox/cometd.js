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

dojo.provide('dojox.cometd');
dojo.registerModulePath('org','../org');
dojo.require('org.cometd');
dojo.require('dojo.io.script');

// Remap cometd JSON functions to dojo JSON functions
org.cometd.JSON.toJSON = dojo.toJson;
org.cometd.JSON.fromJSON = dojo.fromJson;

dojox.Cometd = function(name)
{
    var cometd = new org.cometd.Cometd(name);

    function LongPollingTransport()
    {
        var _super = new org.cometd.LongPollingTransport();
        var that = org.cometd.Transport.derive(_super);

        that.xhrSend = function(packet)
        {
            var deferred = dojo.rawXhrPost({
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
        var _super = new org.cometd.CallbackPollingTransport();
        var that = org.cometd.Transport.derive(_super);

        that.jsonpSend = function(packet)
        {
            var deferred = dojo.io.script.get({
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
    if (org.cometd.WebSocket)
    {
        cometd.registerTransport('websocket', new org.cometd.WebSocketTransport());
    }
    cometd.registerTransport('long-polling', new LongPollingTransport());
    cometd.registerTransport('callback-polling', new CallbackPollingTransport());

    return cometd;
};

// The default cometd instance
dojox.cometd = new dojox.Cometd();

// Create a compatibility API for dojox.cometd instance with
// the original API.

dojox.cometd._init = dojox.cometd.init;

dojox.cometd._unsubscribe = dojox.cometd.unsubscribe;

dojox.cometd.unsubscribe = function(channelOrToken, objOrFunc, funcName)
{
    if (typeof channelOrToken === 'string')
    {
        throw "Deprecated function unsubscribe(string). Use unsubscribe(object) passing as argument the return value of subscribe()";
    }

    dojox.cometd._unsubscribe(channelOrToken);
};

dojox.cometd._metaHandshakeEvent = function(event)
{
    event.action = "handshake";
    dojo.publish("/cometd/meta", [event]);
};

dojox.cometd._metaConnectEvent = function(event)
{
    event.action = "connect";
    dojo.publish("/cometd/meta", [event]);
};

dojox.cometd.addListener('/meta/handshake', dojox.cometd, dojox.cometd._metaHandshakeEvent);
dojox.cometd.addListener('/meta/connect', dojox.cometd, dojox.cometd._metaConnectEvent);
