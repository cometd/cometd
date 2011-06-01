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
 * <p>This servlet serves as a base class for initializing and configuring an
 * instance of the {@link Oort} CometD cluster manager.</p>
 * <p>The following servlet init parameters are used to configure the Oort instance:</p>
 * <ul>
 * <li><code>oort.url</code>, the absolute public URL to the CometD servlet</li>
 * <li><code>oort.channels</code>, a comma separated list of channels that
 * will be passed to {@link Oort#observeChannel(String)}</li>
 * <li><code>clientDebug</code>, a boolean that enables debugging of the
 * clients connected to other oort cluster managers</li>
 * </ul>
 * <p>Override method {@link #newOort(BayeuxServer, String)} to return a customized
 * instance of {@link Oort}.</p>
 *
 * @see SetiServlet
 */
public abstract class OortConfigServlet implements Servlet
{
    public final static String OORT_URL_PARAM = "oort.url";
    public final static String OORT_CHANNELS_PARAM = "oort.channels";

    private ServletConfig _config;

    public ServletConfig getServletConfig()
    {
        return _config;
    }

    public String getServletInfo()
    {
        return OortConfigServlet.class.toString();
    }

    public void init(ServletConfig config) throws ServletException
    {
        _config = config;

        BayeuxServer bayeux = (BayeuxServer)config.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        if (bayeux == null)
            throw new UnavailableException("Missing " + BayeuxServer.ATTRIBUTE + " attribute");

        String url = _config.getInitParameter(OORT_URL_PARAM);
        if (url == null)
            throw new UnavailableException("Missing " + OORT_URL_PARAM + " init parameter");

        try
        {
            Oort oort = newOort(bayeux, url);
            oort.setClientDebugEnabled(Boolean.valueOf(_config.getInitParameter("clientDebug")));
            oort.start();
            _config.getServletContext().setAttribute(Oort.OORT_ATTRIBUTE, oort);

            configureCloud(config, oort);

            String channels = _config.getInitParameter(OORT_CHANNELS_PARAM);
            if (channels != null)
            {
                String[] patterns = channels.split(",");
                for (String channel : patterns)
                {
                    channel = channel.trim();
                    if (channel.length() > 0)
                        oort.observeChannel(channel);
                }
            }
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }

    /**
     * <p>Configures the Oort cloud by establishing connections with other Oort comets.</p>
     * <p>Subclasses implement their own strategy to discover and link with other comets.</p>
     *
     * @param config the servlet configuration to read parameters from
     * @param oort the Oort instance associated with this configuration servlet
     * @throws Exception if the cloud configuration fails
     */
    protected abstract void configureCloud(ServletConfig config, Oort oort) throws Exception;

    protected Oort newOort(BayeuxServer bayeux, String url)
    {
        return new Oort(bayeux, url);
    }

    public void destroy()
    {
        try
        {
            Oort oort = (Oort)_config.getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
            if (oort != null)
                oort.stop();
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        HttpServletResponse response = (HttpServletResponse)res;
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
}
