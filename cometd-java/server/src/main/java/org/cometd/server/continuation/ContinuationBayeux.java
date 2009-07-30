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
//========================================================================

package org.cometd.server.continuation;

import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletContext;

import org.cometd.server.AbstractBayeux;
import org.cometd.server.ClientImpl;
import org.eclipse.jetty.util.thread.Timeout;

/* ------------------------------------------------------------ */
/**
 * Extension of Bayeux that uses {@link ContinuationClient}s.
 * 
 * @author gregw
 * 
 */
public class ContinuationBayeux extends AbstractBayeux
{
    private static int __id;
    private transient Timer _tick;
    private transient Timeout _timeout;
    private long _now;

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see org.cometd.server.AbstractBayeux#newClient(java.lang.String,
     * dojox.io.cometd.Destination)
     */
    @Override
    public ClientImpl newRemoteClient()
    {
        return new ContinuationClient(this);
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see
     * org.cometd.server.AbstractBayeux#initialize(javax.servlet.ServletContext)
     */
    @Override
    protected void initialize(ServletContext context)
    {
        super.initialize(context);

        _tick=new Timer("ContinuationBayeux-" + __id++,true);
        _timeout=new Timeout();
        _timeout.setDuration(getMaxInterval());

        _tick.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                _now=System.currentTimeMillis();
                _timeout.tick(_now);
            }
        },100L,100L);
    }

    /* ------------------------------------------------------------ */
    long getNow()
    {
        return _now;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setMaxInterval(long ms)
    {
        _timeout.setDuration(ms);
        super.setMaxInterval(ms);
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see
     * org.cometd.server.AbstractBayeux#initialize(javax.servlet.ServletContext)
     */
    public void destroy()
    {
        _tick.cancel();
    }

    /* ------------------------------------------------------------ */
    void startTimeout(Timeout.Task timeout, long interval)
    {
        synchronized(_timeout)
        {
            if (interval == 0 || interval==_maxInterval)
                _timeout.schedule(timeout);
            else
            {
                long delay=interval - _maxInterval;
                _timeout.schedule(timeout,delay);
            }
        }
    }

    /* ------------------------------------------------------------ */
    void cancelTimeout(Timeout.Task timeout)
    {
        synchronized(_timeout)
        {
            if (timeout != null)
                timeout.cancel();
        }
    }

}
