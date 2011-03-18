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

import org.cometd.bayeux.server.BayeuxServer;

/**
 * <p>This servlet initializes and configures and instance of the {@link Oort}
 * CometD cluster manager.</p>
 * <p>This servlet must be initialized after an instance the CometD servlet
 * that creates the {@link BayeuxServer} instance used by {@link Oort}.</p>
 * <p>The following servlet init parameters are used to configure Oort:</p>
 * <ul>
 * <li><code>oort.url</code>, the absolute public URL to the CometD servlet</li>
 * <li><code>oort.cloud</code>, a comma separated list of the <code>oort.url</code>s
 * of other known oort CometD cluster managers</li>
 * <li><code>oort.channels</code>, a comma separated list of channels that
 * will be passed to {@link Oort#observeChannel(String)}</li>
 * <li><code>clientDebug</code>, a boolean that enables debugging of the
 * clients connected to other oort cluster managers</li>
 * </ul>
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
        _config = config;

        BayeuxServer bayeux = (BayeuxServer)config.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        if (bayeux == null)
        {
            _config.getServletContext().log("No " + BayeuxServer.ATTRIBUTE + " initialized");
            throw new UnavailableException(BayeuxServer.ATTRIBUTE);
        }

        String url = _config.getInitParameter(Oort.OORT_URL);
        if (url == null)
        {
            _config.getServletContext().log("No " + Oort.OORT_URL + " init parameter");
            throw new UnavailableException(Oort.OORT_URL);
        }

        Oort oort = new Oort(url, bayeux);
        _config.getServletContext().setAttribute(Oort.OORT_ATTRIBUTE, oort);

        oort.setClientDebugEnabled(Boolean.valueOf(_config.getInitParameter("clientDebug")));

        String channels = _config.getInitParameter(Oort.OORT_CHANNELS);
        if (channels != null)
        {
            String[] patterns = channels.split(",");
            for (String channel : patterns)
                oort.observeChannel(channel);
        }

        String cloud = _config.getInitParameter(Oort.OORT_CLOUD);
        if (cloud != null && cloud.length() > 0)
        {
            String[] urls = cloud.split(",");
            for (String comet : urls)
                if (comet.length() > 0)
                    oort.observeComet(comet);
        }
    }

    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        HttpServletResponse response = (HttpServletResponse)res;
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
}
