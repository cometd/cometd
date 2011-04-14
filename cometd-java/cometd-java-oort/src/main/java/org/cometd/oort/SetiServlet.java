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

/**
 * <p>This servlet initializes and configures and instance of the {@link Seti}
 * user mapper.</p>
 * <p>This servlet must be initialized after an instance the Oort servlet
 * that creates the {@link Oort} instance.</p>
 * <p>Override method {@link #newSeti(Oort, String)} to return a customized
 * instance of {@link Seti}.</p>
 *
 * @see OortServlet
 */
public class SetiServlet implements Servlet
{
    private ServletConfig _config;

    public ServletConfig getServletConfig()
    {
        return _config;
    }

    public String getServletInfo()
    {
        return SetiServlet.class.toString();
    }

    public void init(ServletConfig config) throws ServletException
    {
        _config = config;

        Oort oort = (Oort)config.getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
        if (oort == null)
            throw new UnavailableException("Missing " + Oort.OORT_ATTRIBUTE + " attribute");

        try
        {
            Seti seti = newSeti(oort);
            seti.start();
            _config.getServletContext().setAttribute(Seti.SETI_ATTRIBUTE, seti);
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }

    protected Seti newSeti(Oort oort)
    {
        return new Seti(oort);
    }

    public void destroy()
    {
        try
        {
            Seti seti = (Seti)_config.getServletContext().getAttribute(Seti.SETI_ATTRIBUTE);
            if (seti != null)
                seti.stop();
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
