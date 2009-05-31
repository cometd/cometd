// ========================================================================
// Copyright 2007-2008 Mort Bay Consulting Pty. Ltd.
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

package org.cometd.oort;


import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletResponse;

import org.cometd.Bayeux;
import org.cometd.server.AbstractCometdServlet;

/**
 * Oort Servlet.
 * <p>
 * This servlet initializes and configures and instance of the {@link Oort}
 * comet cluster manager.  The servlet must be initialized after an instance
 * of {@link AbstractCometdServlet}, which creates the {@link Bayeux} instance
 * used.
 * <p>
 * The following servlet init parameters are used to configure Oort:<dl>
 * <dt>oort.url</dt><dd>The absolute public URL to the cometd servlet.</dd>
 * <dt>oort.cloud</dt><dd>A comma separated list of the oort.urls of other
 * known oort comet servers that are passed to {@link Oort#observeComet(String)}
 * on startup.</dd>
 * <dt>oort.channels</dt><dd>A comma separated list of channels that will be
 * passed to {@link Oort#observeChannel(String)}</dd>
 * </dl>
 * @author gregw
 *
 */
public class OortServlet implements Servlet
{
    private ServletConfig _config;

    public void destroy()
    {
    }

    public ServletConfig getServletConfig()
    {
        return _config;
    }

    public String getServletInfo()
    {
        return OortServlet.class.toString();
    }

    public void init(ServletConfig config) throws ServletException
    {
        System.err.println("INIT "+config);
        _config=config;

        Bayeux bayeux = (Bayeux)config.getServletContext().getAttribute(Bayeux.ATTRIBUTE);
        if (bayeux==null)
        {
            _config.getServletContext().log("No "+Bayeux.ATTRIBUTE +" initialized");
            throw new UnavailableException(Bayeux.ATTRIBUTE);
        }

        String url=_config.getInitParameter(Oort.OORT_URL);
        if (url==null)
        {
            _config.getServletContext().log("No "+Oort.OORT_URL+" init parameter");
            throw new UnavailableException(Oort.OORT_URL);
        }

        Oort oort= new Oort(url,bayeux);
        _config.getServletContext().setAttribute(Oort.OORT_ATTRIBUTE,oort);

        String channels=_config.getInitParameter(Oort.OORT_CHANNELS);
        if (channels!=null)
        {
            String[] patterns=channels.split("[, ]");
            for (String channel : patterns)
                oort.observeChannel(channel);

        }

        try
        {
            oort.start();
        }
        catch(Exception e)
        {
            throw new ServletException(e);
        }

        String cloud = _config.getInitParameter(Oort.OORT_CLOUD);
        if (cloud!=null&&cloud.length()>0)
        {
            String[] urls=cloud.split("[, ]");
            for (String comet : urls)
                if (comet.length()>0)
                    oort.observeComet(comet);

        }
    }

    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        HttpServletResponse response = (HttpServletResponse)res;
        response.sendError(503);
    }
}
