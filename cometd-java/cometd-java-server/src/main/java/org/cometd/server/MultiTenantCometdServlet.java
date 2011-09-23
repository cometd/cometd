package org.cometd.server;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class MultiTenantCometdServlet extends CometdServlet
{
    private final static Logger LOG = Log.getLogger(MultiTenantCometdServlet.class);
    private final ConcurrentMap<String, BayeuxServerImpl> _bayeux= new ConcurrentHashMap<String, BayeuxServerImpl>();
    
    /**
     * 
     */
    private static final long serialVersionUID = 1869480567465618623L;

    @Override
    public void init() throws ServletException
    {
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String tenantId = getTenantId(request);
        if (tenantId==null)
        {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        BayeuxServerImpl bayeux = _bayeux.get(tenantId);
        try
        {
            if (bayeux==null)
            {
                bayeux=newBayeuxServer();
                addTransports(bayeux);
                allowTransports(bayeux);
                setOptions(bayeux);
                bayeux.start();
                BayeuxServerImpl b=_bayeux.putIfAbsent(tenantId,bayeux);
                if (b==null)
                {
                    LOG.info("New tenant: "+tenantId);
                    customise(bayeux);
                }
                else
                {
                    bayeux.stop();
                    bayeux=b;
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        
        service(bayeux,request,response);
    }

    abstract protected void customise(BayeuxServerImpl bayeux);

    abstract protected String getTenantId(HttpServletRequest request);

    @Override
    public void destroy()
    {
        // TODO Auto-generated method stub
    }
    
}
