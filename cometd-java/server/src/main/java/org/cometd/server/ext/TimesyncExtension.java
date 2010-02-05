// ========================================================================
// Copyright 2006 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cometd.server.ext;

import java.util.Map;

import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.server.ClientImpl;
import org.eclipse.jetty.util.ajax.JSON;

/* ------------------------------------------------------------ */
/**
 * Timesync extension (server side).
 * <p/>
 * With each handshake or connect, the extension sends timestamps within the ext
 * field like:
 * <code>{ext:{timesync:{tc:12345567890,l:23,o:4567},...},...}</code> where:
 * <ul>
 * <li>tc is the client timestamp in ms since 1970 of when the message was sent.
 * <li>l is the network lag that the client has calculated.
 * <li>o is the clock offset that the client has calculated.
 * </ul>
 * <p/>
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
 * The relationship between tc, ts, o & l on the server is given by
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
public class TimesyncExtension implements Extension
{
    private int _accuracyTarget = 25;

    /**
     * timesync responses are not set if the measured accuracy is less than the
     * accuracyTarget.
     *
     * @return accuracy target in ms (default 25ms)
     */
    public int getAccuracyTarget()
    {
        return _accuracyTarget;
    }

    /**
     * timesync responses are not set if the measured accuracy is less than the
     * accuracyTarget.
     *
     * @param target accuracy target in ms
     */
    public void setAccuracyTarget(int target)
    {
        _accuracyTarget = target;
    }

    public Message rcv(Client from, Message message)
    {
        return message;
    }

    public Message rcvMeta(Client from, Message message)
    {
        Map<String, Object> ext = message.getExt(false);
        if (ext != null)
        {
            Map<String, Object> sync = (Map<String, Object>)ext.get("timesync");
            if (sync != null)
            {
                sync.put("ts", System.currentTimeMillis());
                Number lag = (Number)sync.get("l");
                if (lag != null && from instanceof ClientImpl)
                    ((ClientImpl)from).setLag(lag.intValue());
            }
        }
        return message;
    }

    public Message send(Client from, Message message)
    {
        return message;
    }

    public Message sendMeta(Client from, Message message)
    {
        Message associated = message.getAssociated();
        if (associated != null)
        {
            Map<String, Object> extIn = associated.getExt(false);
            if (extIn != null)
            {
                Map<String, Object> sync = (Map<String, Object>)extIn.get("timesync");
                if (sync != null)
                {
                    final long tc = ((Number)sync.get("tc")).longValue();
                    final long ts = ((Number)sync.get("ts")).longValue();

                    StringBuilder buffer = new StringBuilder(80);
                    final Number lag = (Number)sync.get("l");
                    if (lag == null)
                    {
                        // Old style timesync
                        Map<String, Object> extOut = message.getExt(true);
                        buffer.append("{\"tc\":").append(tc)
                                .append(",\"ts\":").append(ts)
                                .append(",\"p\":").append(System.currentTimeMillis() - ts)
                                .append("}");
                        extOut.put("timesync", new JSON.Literal(buffer.toString()));
                    }
                    else
                    {
                        final int l = lag.intValue();
                        Number offset = (Number)sync.get("o");
                        final int o = offset == null ? 0 : offset.intValue(); // May return null if is NaN
                        final int a = (int)((tc + o + l) - ts);

                        // Within accuracy ?
                        if (l == 0 || a >= _accuracyTarget || a <= -_accuracyTarget)
                        {
                            Map<String, Object> extOut = message.getExt(true);
                            buffer.append("{\"tc\":").append(tc)
                                    .append(",\"ts\":").append(ts)
                                    .append(",\"p\":").append(System.currentTimeMillis() - ts)
                                    .append(",\"a\":").append(a)
                                    .append("}");
                            extOut.put("timesync", new JSON.Literal(buffer.toString()));
                        }
                    }
                }
            }
        }
        return message;
    }
}
