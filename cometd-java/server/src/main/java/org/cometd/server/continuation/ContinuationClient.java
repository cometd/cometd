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

import javax.servlet.http.HttpServletResponse;

import org.cometd.server.ClientImpl;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;

/* ------------------------------------------------------------ */
/**
 * Extension of {@link ClientImpl} that uses {@link Continuation}s to resume
 * clients waiting for messages. Continuation clients are used for remote
 * clients and are removed if they are not accessed within an idle timeout.
 *
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
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
        synchronized (this)
        {
            Continuation oldContinuation = _continuation;
            _continuation = continuation;
            Log.debug("Old continuation {}, new continuation {}", oldContinuation, continuation);

            // There can be a suspended old continuation if the remote client reloads or
            // somehow re-issues a new long poll request with an outstanding long poll request.
            // In this case we complete() the existing continuation, otherwise
            // it will expire, this method is entered again with a null argument, and a timeout
            // to expire this client will be scheduled (which is wrong because client expiration
            // must be handled by the new continuation and not by the old one, which dies here)
            if (oldContinuation != null && oldContinuation.isSuspended())
            {
                try
                {
                    int responseCode = HttpServletResponse.SC_REQUEST_TIMEOUT;
                    Log.debug("Sending {} on old continuation {}", responseCode, oldContinuation);
                    HttpServletResponse response = (HttpServletResponse)oldContinuation.getServletResponse();
                    response.sendError(responseCode);
                }
                catch(Exception x)
                {
                    Log.ignore(x);
                }

                try
                {
                    Log.debug("Completing old continuation {}", oldContinuation);
                    oldContinuation.complete();
                }
                catch (Exception x)
                {
                    Log.ignore(x);
                }
            }

            if (continuation == null)
            {
                // Set timeout when to expect the next long poll
                if (_intervalTimeoutTask != null)
                    _bayeux.startTimeout(_intervalTimeoutTask, _bayeux.getMaxInterval());
            }
            else
            {
                _bayeux.cancelTimeout(_intervalTimeoutTask);
                _accessed = _bayeux.getNow();
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
