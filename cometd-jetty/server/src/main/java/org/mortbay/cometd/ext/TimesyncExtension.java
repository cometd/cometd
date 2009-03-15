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

package org.mortbay.cometd.ext;

import java.util.Map;

import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.mortbay.cometd.ClientImpl;
import org.mortbay.util.ajax.JSON;



/* ------------------------------------------------------------ */
/**
 * Timesync extension (server side).
 * 
 * With each handshake or connect, the extension sends timestamps within the 
 * ext field like: <code>{ext:{timesync:{tc:12345567890,l:23,o:4567},...},...}</code>
 * where:<ul>
 *  <li>tc is the client timestamp in ms since 1970 of when the message was sent.
 *  <li>l is the network lag that the client has calculated.
 *  <li>o is the clock offset that the client has calculated.
 * </ul>
 * The accuracy of the offset and lag may be calculated with tc-now-l-o,
 * which should be zero if the calculated offset and lag are perfectly
 * accurate.
 * <p>
 * A cometd server that supports timesync, should respond only if the
 * measured accuracy value is greater than accuracy target. The response
 * will be an ext field like: <code>{ext:{timesync:{tc:12345567890,ts:1234567900,p:123,a:3},...},...}</code>
 * where:<ul>
 *  <li>tc is the client timestamp of when the message was sent,
 *  <li>ts is the server timestamp of when the message was received
 *  <li>p is the poll duration in ms - ie the time the server took before sending the response.
 *  <li>a is the measured accuracy of the calculated offset and lag sent by the client
 * </ul>
 * 
 * On receipt of the response, the client is able to use current time to determine
 * the total trip time, from which p is subtracted to determine an approximate
 * two way network traversal time. The measured accuracy is used to adjust the assumption 
 * that the network is symmetric for traversal time, so: <ul>
 * <li>lag = (now-tc-p)/2-a
 * <li>offset = ts-tc-lag
 * </ul>
 *
 */
public class TimesyncExtension implements Extension
{
    private int _accuracyTarget=25;
    
    public TimesyncExtension()
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * timesync responses are not set if the measured accuracy is
     * less than the accuracyTarget.
     * @return accuracy target in ms (default 25ms)
     */
    public int getAccuracyTarget()
    {
        return _accuracyTarget;
    }

    /* ------------------------------------------------------------ */
    /**
     * timesync responses are not set if the measured accuracy is
     * less than the accuracyTarget.
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
        Map<String,Object> ext=message.getExt(false);
        if (ext!=null)
        {
            Map<String,Object> sync=(Map<String,Object>)ext.get("timesync");
            if (sync!=null)
            {
                sync.put("ts",new Long(System.currentTimeMillis()));
                Number lag=(Number)sync.get("l");
                if (lag!=null && from !=null)
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
        if (associated!=null)
        {
            Map<String,Object> extIn=associated.getExt(false);
            
            if (extIn!=null)
            {
                Map<String,Object> sync=(Map<String,Object>)extIn.get("timesync");
                if (sync!=null)
                {
                    final long tc=((Number)sync.get("tc")).longValue();
                    final long ts=((Number)sync.get("ts")).longValue();
                    
                    Number lag=(Number)sync.get("l");
                    if (lag==null)
                    {
                        // old style timesync
                        Map<String,Object> extOut=(Map<String,Object>)message.getExt(true);
                        JSON.Literal timesync=new JSON.Literal("{\"tc\":"+tc+",\"ts\":"+ts+",\"p\":"+(System.currentTimeMillis()-ts)+"}");
                        extOut.put("timesync",timesync);
                    }
                    else
                    {
                        final int l=lag.intValue();
                        final int o=((Number)sync.get("o")).intValue();
                        final int a=(int)(tc-ts)+o+l;

                        // is a OK ?
                        if (a>=_accuracyTarget||a<=-_accuracyTarget)
                        {
                            Map<String,Object> extOut=(Map<String,Object>)message.getExt(true);
                            JSON.Literal timesync=new JSON.Literal("{\"tc\":"+tc+",\"ts\":"+ts+",\"p\":"+(System.currentTimeMillis()-ts)+",\"a\":"+a+"}");
                            extOut.put("timesync",timesync);
                        }
                    }
                }
            }
        }
        return message;
    }
}