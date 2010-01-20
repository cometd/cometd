//========================================================================
// Copyright 2008 Mort Bay Consulting Pty. Ltd.
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
//========================================================================

package org.cometd.client;

import java.util.Map;

/**
 * Advice
 *
 * Implementation of advice field from Bayeux message.
 * 
 */
public class Advice
{
    //reconnect: retry, handshake, none
    public static final int __RECONNECT_RETRY = 0;
    public static final int __RECONNECT_HANDSHAKE = 1;
    public static final int __RECONNECT_NONE = 2;
    private Integer _reconnect;
    
    //interval: ms
    private Long _interval;
    
    //multiple-clients: boolean
    private Boolean _multipleClients;
    
    //hosts: list
    private String[] _hosts;
    
    private Map _adviceField;
    
    
    public Advice (Map adviceField)
    {
        _adviceField = adviceField;
    }
    
    /**
     * A client MAY attempt to reconnect with a /meta/connect after 
     * the interval (as defined by "interval" advice or client-default backoff), 
     * and with the same credentials. 
     * @return
     */
    public boolean isReconnectRetry ()
    {
        if (_reconnect == null && _adviceField != null)
             _reconnect = parseReconnect((String)_adviceField.get("reconnect"));        

        return _reconnect != null && _reconnect.intValue() ==__RECONNECT_RETRY;
    }
    
    /**
     * The server has terminated any prior connection status and 
     * the client MUST reconnect with a /meta/handshake. A client 
     * MUST NOT automatically retry if handshake advice has been received.
     * @return
     */
    public boolean isReconnectHandshake ()
    { 
        if (_reconnect == null && _adviceField != null)
            _reconnect = parseReconnect((String)_adviceField.get("reconnect"));  

        return _reconnect != null && _reconnect.intValue() == __RECONNECT_HANDSHAKE;
    }

    /**
     * Hard failure for the connect attempt. Do not attempt to 
     * reconnect at all. A client MUST respect reconnect advice 
     * of none and MUST NOT automatically retry or handshake.
     * @return
     */
    public boolean isReconnectNone ()
    {
        if (_reconnect == null && _adviceField != null)
            _reconnect = parseReconnect((String)_adviceField.get("reconnect"));  

        return _reconnect != null && _reconnect.intValue() == __RECONNECT_NONE;
    }
    
    public long getInterval()
    {
        if (_interval==null && _adviceField != null)
            _interval = (Long)_adviceField.get("interval");
        
        return (_interval==null?0:_interval.longValue());
    }
    
    public boolean isMultipleClients()
    {
        if (_multipleClients == null && _adviceField != null)
            _multipleClients = (Boolean)_adviceField.get("multiple-clients");

        return (_multipleClients==null?false:_multipleClients.booleanValue());
    }
    
    public String[] getHosts()
    {
        if (_hosts == null && _adviceField != null)
        {
            Object[] hosts = (Object[])_adviceField.get("hosts");
            if (hosts!=null)
            {
                _hosts = new String[hosts.length];
                System.arraycopy(hosts, 0, _hosts, 0, hosts.length);
            }  
        }
        return _hosts;
    }

    private Integer parseReconnect (String reconnect)
    {
        if (reconnect != null)
        {
            reconnect = reconnect.trim();
            if (reconnect.equalsIgnoreCase("retry"))
                return new Integer( __RECONNECT_RETRY);
            else if (reconnect.equalsIgnoreCase("handshake"))
                return new Integer(__RECONNECT_HANDSHAKE);
            else if (reconnect.equalsIgnoreCase("none"))
               return new Integer(__RECONNECT_NONE);
        }
        return null;
    }
}
