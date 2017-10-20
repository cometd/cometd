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
package org.cometd.server.ext;

import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;

/**
 * Timesync extension (server side).
 * <p>
 * With each handshake or connect, the extension sends timestamps within the ext
 * field like:
 * <code>{ext:{timesync:{tc:12345567890,l:23,o:4567},...},...}</code> where:
 * <ul>
 * <li>tc is the client timestamp in ms since 1970 of when the message was sent.
 * <li>l is the network lag that the client has calculated.
 * <li>o is the clock offset that the client has calculated.
 * </ul>
 * <p>
 * <p>
 * A cometd server that supports timesync, can respond with an ext field like:
 * <code>{ext:{timesync:{tc:12345567890,ts:1234567900,p:123,a:3},...},...}</code>
 * where:
 * <ul>
 * <li>tc is the client timestamp of when the message was sent,
 * <li>ts is the server timestamp of when the message was received
 * <li>p is the poll duration in ms - ie the time the server took before sending
 * the response.
 * <li>a is the measured accuracy of the calculated offset and lag sent by the
 * client
 * </ul>
 * <p>
 * The relationship between tc, ts, o and l on the server is given by
 * <code>ts=tc+o+l</code> (the time the server received the message is the
 * client time plus the offset plus the network lag). Thus the accuracy of the o
 * and l settings can be determined with <code>a=tc+o+l-ts</code>.
 * </p>
 * <p>
 * When the client has received the response, it can make a more accurate
 * estimate of the lag as <code>l2=(now-tc-p)/2</code> (assuming symmetric lag).
 * A new offset can then be calculated with the relationship on the client that
 * <code>ts=tc+o2+l2</code>, thus <code>o2=ts-tc-l2</code>.
 * </p>
 * <p>
 * Since the client also receives the a value calculated on the server, it
 * should be possible to analyse this and compensate for some asymmetry in the
 * lag. But the current client does not do this.
 * </p>
 */
public class TimesyncExtension implements Extension {
    public static final String LAG_ATTRIBUTE = "lag";

    private int _accuracyTarget = 25;

    public TimesyncExtension() {
    }

    /**
     * timesync responses are not set if the measured accuracy is less than the
     * accuracyTarget.
     *
     * @return accuracy target in ms (default 25ms)
     */
    public int getAccuracyTarget() {
        return _accuracyTarget;
    }

    /**
     * timesync responses are not set if the measured accuracy is less than the
     * accuracyTarget.
     *
     * @param target accuracy target in ms
     */
    public void setAccuracyTarget(int target) {
        _accuracyTarget = target;
    }

    @Override
    public boolean rcvMeta(ServerSession from, Mutable message) {
        Map<String, Object> ext = message.getExt(false);
        if (ext != null) {
            Map<String, Object> sync = (Map<String, Object>)ext.get("timesync");
            if (sync != null) {
                sync.put("ts", System.currentTimeMillis());
                Number lag = (Number)sync.get("l");
                if (lag != null && from != null) {
                    from.setAttribute(LAG_ATTRIBUTE, lag);
                }
            }
        }
        return true;
    }

    @Override
    public boolean sendMeta(ServerSession session, Mutable message) {
        ServerMessage associated = message.getAssociated();
        if (associated != null) {
            Map<String, Object> extIn = associated.getExt();

            if (extIn != null) {
                Map<String, Object> sync = (Map<String, Object>)extIn.get("timesync");
                if (sync != null) {
                    final long tc = ((Number)sync.get("tc")).longValue();
                    final long ts = ((Number)sync.get("ts")).longValue();

                    final Number lag = (Number)sync.get("l");
                    if (lag == null) {
                        // old style timesync
                        Map<String, Object> extOut = message.getExt(true);
                        Map<String, Object> timesync = new HashMap<>(3);
                        timesync.put("tc", tc);
                        timesync.put("ts", ts);
                        timesync.put("p", System.currentTimeMillis() - ts);
                        extOut.put("timesync", timesync);
                    } else {
                        final int l = lag.intValue();
                        final int o = ((Number)sync.get("o")).intValue();
                        final int a = (int)((tc + o + l) - ts);

                        // is a OK ?
                        if (l == 0 || a >= _accuracyTarget || a <= -_accuracyTarget) {
                            Map<String, Object> extOut = message.getExt(true);
                            Map<String, Object> timesync = new HashMap<>(4);
                            timesync.put("tc", tc);
                            timesync.put("ts", ts);
                            timesync.put("p", System.currentTimeMillis() - ts);
                            timesync.put("a", a);
                            extOut.put("timesync", timesync);
                        }
                    }
                }
            }
        }
        return true;
    }
}
