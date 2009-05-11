/**
 * Copyright 2008-2009 Mort Bay Consulting Pty. Ltd.
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * ----------------------------------------------------------------------------
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http: *www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ----------------------------------------------------------------------------
 * Licensed under the MIT license;
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * ----------------------------------------------------------------------------
 * $Revision$ $Date$
 */

if (typeof dojo!="undefined") dojo.provide("org.cometd.TimeSyncExtension");

/**
 * The timesync extension implements an algorithm similar to NTP (network time protocol)
 * to derive the offset between the client time and the server time.
 *
 * For every message on the /meta/* channels, this extension adds the following object
 * to the bayeux 'ext' object:
 * <pre>
 * timesync: {
 *     tc: 1234567890
 *     l:  123
 *     o:  4567
 * }
 * </pre>
 * where:
 * <ul>
 * <li><b>tc</b> is the client timestamp of when the message was sent, in milliseconds</li>
 * <li><b>l</b> is the network lag that the client has calculated</li>
 * <li><b>o</b> is the clock offset that the client has calculated</li>
 * </ul>
 * The accuracy of the offset and lag may be calculated with tc - now - l - o,
 * which should be zero if the calculated offset and lag are perfectly accurate.
 *
 * A cometd server that supports timesync, should respond only if the
 * measured accuracy value is greater than accuracy target.
 * The response is an object in the bayeux 'ext' object:
 * <pre>
 * timesync: {
 *     tc: 1234567890,
 *     ts: 1234567899,
 *     p:  123,
 *     a:  4
 * }
 * </pre>
 * where:
 * <ul>
 * <li>tc is the client timestamp of when the message was sent</li>
 * <li>ts is the server timestamp of when the message was received</li>
 * <li>p is the processing time in ms, i.e. the time the server took before sending the response</li>
 * <li>a is the measured accuracy of the offset and lag sent by the client</li>
 * </ul>
 * On receipt of the response, the client is able to use current time to determine
 * the total network trip time, from which <b>p</b> is subtracted to determine an approximate
 * two way network traversal time.
 * The measured accuracy is used to adjust the assumption that the network is symmetric for traversal time, so:
 * <pre>
 * lag = (now - tc - p) / 2 - a;
 * offset = ts - tc - lag;
 * </pre>
 * In order to smooth over any transient fluctuations, the extension keeps a sliding
 * average of the offsets received, over a number of samples configurable via the <b>maxSamples</b>
 * parameter:
 * <pre>
 * var cometd = ...;
 * cometd.registerExtension('timesync', new org.cometd.TimeSyncExtension({
 *     maxSamples: 20
 * }));
 * cometd.init(...);
 * var timesync = cometd.getExtension('timesync');
 * var timeOffset = timesync.getTimeOffset();
 * </pre>
 * @param configuration
 */

org.cometd.TimeSyncExtension = function(configuration)
{
    var _cometd;
    var _maxSamples = configuration && configuration.maxSamples || 10;
    var _lags = [];
    var _offsets = [];
    var _lag = 0;
    var _offset = 0;

    this.registered = function(name, cometd)
    {
        _cometd = cometd;
        _debug('TimeSyncExtension: executing registration callback');
    };

    this.unregistered = function()
    {
        _debug('TimeSyncExtension: executing unregistration callback');
        _cometd = null;
        _lags = [];
        _offsets = [];
    };

    this.incoming = function(message)
    {
        var channel = message.channel;
        if (channel && channel.indexOf('/meta/') == 0)
        {
            if (message.ext && message.ext.timesync)
            {
                var timesync = message.ext.timesync;
                _debug('TimeSyncExtension: server sent timesync: {}', org.cometd.JSON.toJSON(timesync));

                var now = new Date().getTime();
                var currentLag = (now - timesync.tc - timesync.p) / 2 - timesync.a;
                var currentOffset = timesync.ts - timesync.tc - currentLag;

                _lags.push(currentLag);
                _offsets.push(currentOffset);
                if (_offsets.length > _maxSamples)
                {
                    _offsets.shift();
                    _lags.shift();
                }

                var samples = _offsets.length;
                var lagsSum = 0;
                var offsetsSum = 0;
                for (var i = 0; i < samples; ++i)
                {
                    lagsSum += _lags[i];
                    offsetsSum += _offsets[i];
                }
                _lag = parseInt((lagsSum / samples).toFixed());
                _offset = parseInt((offsetsSum / samples).toFixed());
                _debug('TimeSyncExtension: network lag {} ms, time offset with server {} ms', _lag, _offset);
            }
        }
        return message;
    };

    this.outgoing = function(message)
    {
        var channel = message.channel;
        if (channel && channel.indexOf('/meta/') == 0)
        {
            if (!message.ext) message.ext = {};
            message.ext.timesync = {
                tc: new Date().getTime(),
                l: _lag,
                o: _offset
            };
            _debug('TimeSyncExtension: client sending timesync: {}', org.cometd.JSON.toJSON(message.ext.timesync));
        }
        return message;
    };

    /**
     * Get the estimated offset in ms from the clients clock to the
     * servers clock.  The server time is the client time plus the offset.
     */
    this.getTimeOffset = function()
    {
        return _offset;
    };

    /**
     * Get an array of multiple offset samples used to calculate
     * the offset.
     */
    this.getTimeOffsetSamples = function()
    {
        return _offsets;
    };

    /**
     * Get the estimated network lag in ms from the client to the server.
     */
    this.getNetworkLag = function()
    {
        return _lag;
    };
    
    /**
     * Get the estimated server time in ms since the epoch.
     */
    this.getServerTime = function()
    { 
        return new Date().getTime()+_offset;
    };
        
    /**
     * 
     * Get the estimated server time as a Date object
     */
    this.getServerDate = function()
    { 
        return new Date(this.getServerTime());
    };
    
    /**
     * Set a timeout to expire at given time on the server.
     * @param callback The function to call when the timer expires
     * @param atServerTimeOrDate a js Time or Date object representing the 
     * server time at which the timeout should expire
     */
    this.setTimeout = function(callback, atServerTimeOrDate)
    {
        var ts = (atTimeOrDate instanceof Date) ? atTimeOrDate.getTime() : (0 + atTimeOrDate);
        var tc = ts - _offset;
        var interval = tc - new Date().getTime();
        if(interval <= 0)
	{
            interval = 1;
        }
        return setTimeout(callback,interval);
    };

    /**
     * 
     */
    function _debug(text, args)
    {
        _cometd._debug(text, args);
    };
}
