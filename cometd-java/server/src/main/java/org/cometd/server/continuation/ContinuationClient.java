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

package org.cometd.server.continuation;

import org.cometd.server.ClientImpl;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.util.thread.Timeout;

/* ------------------------------------------------------------ */
/**
 * Extension of {@link ClientImpl} that uses {@link Continuation}s to resume
 * clients waiting for messages. Continuation clients are used for remote
 * clients and have removed if they are not accessed within an idle timeout (@link
 * {@link ContinuationBayeux#_clientTimer}).
 * 
 * @author gregw
 * 
 */
public class ContinuationClient extends ClientImpl
{
    private final ContinuationBayeux _bayeux;
    private final Timeout.Task _intervalTimeoutTask;
    private final Timeout.Task _lazyTimeoutTask;
    private long _accessed;
    private volatile Continuation _continuation;
    private volatile boolean _lazyResuming;

    /* ------------------------------------------------------------ */
    protected ContinuationClient(ContinuationBayeux bayeux)
    {
        super(bayeux);
        _bayeux=bayeux;

        if (isLocal())
        {
            _intervalTimeoutTask=null;
            _lazyTimeoutTask=null;
        }
        else
        {
            // The timeout task for when a long poll does not arrive.
            _intervalTimeoutTask=new Timeout.Task()
            {
                @Override
                public void expired()
                {
                    remove(true);
                }

                @Override
                public String toString()
                {
                    return "T-" + ContinuationClient.this.toString();
                }
            };

            // The timeout task for lazy messages
            _lazyTimeoutTask=new Timeout.Task()
            {
                @Override
                public void expired()
                {
                    _lazyResuming=false;
                    if (hasMessages())
                        resume();
                }

                @Override
                public String toString()
                {
                    return "L-" + ContinuationClient.this.toString();
                }
            };
            
            _bayeux.startTimeout(_intervalTimeoutTask,_bayeux.getMaxInterval());
        }
    }

    /* ------------------------------------------------------------ */
    public void setContinuation(Continuation continuation)
    {
        if (continuation == null)
        {
            synchronized(this)
            {
                // This is the end of a long poll
                
                // resume any prior continuation
                if (_continuation != null)
                    _continuation.resume();
                _continuation=null;
                
                // Set timeout when to expect the next long poll
                if (_intervalTimeoutTask != null)
                    _bayeux.startTimeout(_intervalTimeoutTask,_bayeux.getMaxInterval());
            }
        }
        else
        {
            synchronized(this)
            {
                // This is the start of a long poll

                // resume any prior continuation
                if (_continuation != null)
                    _continuation.resume();
                _continuation=continuation;

                // Cancel timeout expecting the next long poll
                _bayeux.cancelTimeout(_intervalTimeoutTask);
                _accessed=_bayeux.getNow();
            }
        }
    }

    /* ------------------------------------------------------------ */
    public Continuation getContinuation()
    {
        return _continuation;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void lazyResume()
    {
        int max=_bayeux.getMaxLazyLatency();
        if (max>0 && _lazyTimeoutTask!=null && !_lazyResuming)
        {
            _lazyResuming=true;
            // use modulo so all lazy clients do not wakeup at once
            _bayeux.startTimeout(_lazyTimeoutTask,_accessed%max); 
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void resume()
    {
        synchronized(this)
        {
            if (_continuation != null)
            {
                _continuation.resume();
            }
            _continuation=null;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isLocal()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public void access()
    {
        synchronized(this)
        {
            _accessed=_bayeux.getNow();
            if (_intervalTimeoutTask != null && _intervalTimeoutTask.isScheduled())
            {
                // reschedule the timer even though it may be cancelled next...
                // it might not be.
                _intervalTimeoutTask.reschedule();
            }
        }
    }

    /* ------------------------------------------------------------ */
    public synchronized long lastAccessed()
    {
        return _accessed;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see org.cometd.server.ClientImpl#remove(boolean)
     */
    @Override
    public void remove(boolean wasTimeout)
    {
        synchronized(this)
        {
            if (!wasTimeout && _intervalTimeoutTask != null)
                _bayeux.cancelTimeout(_intervalTimeoutTask);
        }
        super.remove(wasTimeout);
    }
}
