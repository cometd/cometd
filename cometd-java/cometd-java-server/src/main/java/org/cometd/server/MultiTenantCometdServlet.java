package org.cometd.server;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class MultiTenantCometdServlet extends CometdServlet
{
    protected final Logger _logger = LoggerFactory.getLogger(getClass());
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
                    _logger.info("New tenant: "+tenantId);
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
            _logger.warn("",e);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        
        service(bayeux,request,response);
    }

    @Override
    public BayeuxServerImpl getBayeux()
    {
        throw new UnsupportedOperationException("Multitenanted mode");
    }

    abstract protected void customise(BayeuxServerImpl bayeux);

    abstract protected String getTenantId(HttpServletRequest request);

    @Override
    public void destroy()
    {
        for (BayeuxServerImpl bayeux : _bayeux.values())
        {
            try
            {
                bayeux.stop();
            }
            catch (Exception e)
            {
                _logger.warn("",e);
            }
        }
    }
    
}
