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

package org.mortbay.cometd.continuation;

import org.mortbay.cometd.ClientImpl;
import org.mortbay.thread.Timeout;
import org.mortbay.util.ajax.Continuation;

/* ------------------------------------------------------------ */
/**
 * Extension of {@link ClientImpl} that uses {@link Continuation}s to
 * resume clients waiting for messages. Continuation clients are used for
 * remote clients and have removed if they are not accessed within
 * an idle timeout (@link {@link ContinuationBayeux#_clientTimer}).
 * 
 * @author gregw
 *
 */
public class ContinuationClient extends ClientImpl
{
    private long _accessed;
    public transient Timeout.Task _timeout; 
    private ContinuationBayeux _bayeux;
    private transient Continuation _continuation;

    /* ------------------------------------------------------------ */
    protected ContinuationClient(ContinuationBayeux bayeux)
    {
        super(bayeux);
        _bayeux=bayeux;

        if (!isLocal())
        {
            _timeout=new Timeout.Task()
            {
                @Override
                                public void expired()
                {
                    remove(true);
                }
                @Override
                                public String toString()
                {
                    return "T-"+ContinuationClient.this.toString();
                }
            };
            _bayeux.startTimeout(_timeout,getTimeout());
        }
    }


    /* ------------------------------------------------------------ */
    public void setContinuation(Continuation continuation)
    {
        if (continuation==null)
        {
            synchronized (this)
            {
                if (_continuation!=null)
                {
                    if(_continuation.isPending())
                        _continuation.resume(); 
                }
                _continuation=null;
                if(_timeout!=null)
                    _bayeux.startTimeout(_timeout,getTimeout());
            }
        }
        else
        {
            synchronized (this)
            {
                if (_continuation!=null)
                {
                    if(_continuation.isPending())
                        _continuation.resume(); 
                }
                _continuation=continuation;
                _bayeux.cancelTimeout(_timeout);
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
        public void resume()
    {
        synchronized (this)
        {
            if (_continuation!=null)
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
            // distribute access time in cluster
            _accessed=_bayeux.getNow();
            if (_timeout!=null && _timeout.isScheduled())
            {
                _timeout.reschedule();
            }
        }
    }


    /* ------------------------------------------------------------ */
    public synchronized long lastAccessed()
    {
        return _accessed;
    }
    
    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.mortbay.cometd.ClientImpl#remove(boolean)
     */
    @Override
    public void remove(boolean wasTimeout) 
    {
        synchronized(this)
        {
            if (!wasTimeout && _timeout!=null)
                _bayeux.cancelTimeout(_timeout);
            _timeout=null;
        }
        super.remove(wasTimeout);
    }

}