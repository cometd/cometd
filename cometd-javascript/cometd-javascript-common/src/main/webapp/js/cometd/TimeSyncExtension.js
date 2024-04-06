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

import {Extension} from "./Extension.js";

/**
 * With each handshake or connect, the extension sends timestamps within the
 * ext field like: <code>{ext:{timesync:{tc:12345567890,l:23,o:4567},...},...}</code>
 * where:<ul>
 *  <li>tc is the client timestamp in ms since 1970 of when the message was sent.
 *  <li>l is the network lag that the client has calculated.
 *  <li>o is the clock offset that the client has calculated.
 * </ul>
 *
 * <p>
 * A CometD server that supports timesync, can respond with an ext
 * field like: <code>{ext:{timesync:{tc:12345567890,ts:1234567900,p:123,a:3},...},...}</code>
 * where:<ul>
 *  <li>tc is the client timestamp of when the message was sent,
 *  <li>ts is the server timestamp of when the message was received
 *  <li>p is the poll duration in ms - ie the time the server took before sending the response.
 *  <li>a is the measured accuracy of the calculated offset and lag sent by the client
 * </ul>
 *
 * <p>
 * The relationship between tc, ts & l is given by <code>ts=tc+o+l</code> (the
 * time the server received the message is the client time plus the offset plus the
 * network lag).   Thus the accuracy of the o and l settings can be determined with
 * <code>a=(tc+o+l)-ts</code>.
 * </p>
 * <p>
 * When the client has received the response, it can make a more accurate estimate
 * of the lag as <code>l2=(now-tc-p)/2</code> (assuming symmetric lag).
 * A new offset can then be calculated with the relationship on the client
 * that <code>ts=tc+o2+l2</code>, thus <code>o2=ts-tc-l2</code>.
 * </p>
 * <p>
 * Since the client also receives the a value calculated on the server, it
 * should be possible to analyse this and compensate for some asymmetry
 * in the lag. But the current client does not do this.
 * </p>
 */
export class TimeSyncExtension extends Extension {
    #maxSamples;
    #lags = [];
    #offsets = [];
    #lag = 0;
    #offset = 0;

    constructor(configuration) {
        super();
        this.#maxSamples = (configuration && configuration.maxSamples) || 10;
    }

    registered(name, cometd) {
        super.registered(name, cometd);
        this.cometd._debug("TimeSyncExtension: executing registration callback");
    };

    unregistered() {
        this.cometd._debug("TimeSyncExtension: executing unregistration callback");
        super.unregistered();
        this.#lags = [];
        this.#offsets = [];
        this.#lag = 0;
        this.#offset = 0;
    };

    incoming(message) {
        const channel = message.channel;
        if (channel && channel.indexOf("/meta/") === 0) {
            if (message.ext && message.ext.timesync) {
                const timesync = message.ext.timesync;
                this.cometd._debug("TimeSyncExtension: server sent timesync", timesync);

                const now = new Date().getTime();
                const l2 = (now - timesync.tc - timesync.p) / 2;
                const o2 = timesync.ts - timesync.tc - l2;

                this.#lags.push(l2);
                this.#offsets.push(o2);
                if (this.#offsets.length > this.#maxSamples) {
                    this.#offsets.shift();
                    this.#lags.shift();
                }

                const samples = this.#offsets.length;
                let lagsSum = 0;
                let offsetsSum = 0;
                for (let i = 0; i < samples; ++i) {
                    lagsSum += this.#lags[i];
                    offsetsSum += this.#offsets[i];
                }
                this.#lag = parseInt((lagsSum / samples).toFixed());
                this.#offset = parseInt((offsetsSum / samples).toFixed());
                this.cometd._debug("TimeSyncExtension: network lag", this.#lag, "ms, time offset with server", this.#offset, "ms");
            }
        }
        return message;
    };

    outgoing(message) {
        const channel = message.channel;
        if (channel && channel.indexOf("/meta/") === 0) {
            if (!message.ext) {
                message.ext = {};
            }
            message.ext.timesync = {
                tc: new Date().getTime(),
                l: this.getNetworkLag(),
                o: this.getTimeOffset(),
            };
            this.cometd._debug("TimeSyncExtension: client sending timesync", message.ext.timesync);
        }
        return message;
    };

    /**
     * Get the estimated offset in ms from the clients clock to the
     * servers clock.  The server time is the client time plus the offset.
     */
    getTimeOffset() {
        return this.#offset;
    }

    /**
     * Get the estimated network lag in ms from the client to the server.
     */
    getNetworkLag() {
        return this.#lag;
    }

    /**
     * Get the estimated server time in ms since the epoch.
     */
    getServerTime() {
        return new Date().getTime() + this.getTimeOffset();
    }

    /**
     * Get the estimated server time as a Date object
     */
    getServerDate() {
        return new Date(this.getServerTime());
    };

    /**
     * Set a timeout to expire at given time on the server.
     * @param callback The function to call when the timer expires
     * @param atServerTimeOrDate a js Time or Date object representing the
     * server time at which the timeout should expire
     */
    setTimeout(callback, atServerTimeOrDate) {
        const ts = atServerTimeOrDate instanceof Date ? atServerTimeOrDate.getTime() : 0 + atServerTimeOrDate;
        const tc = ts - this.getTimeOffset();
        let interval = tc - new Date().getTime();
        if (interval < 0) {
            interval = 0;
        }
        return this.cometd.setTimeout(callback, interval);
    };
}
