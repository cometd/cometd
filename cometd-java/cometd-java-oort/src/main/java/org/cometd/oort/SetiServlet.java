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


/* ------------------------------------------------------------ */
public class SetiServlet implements Servlet
{    
    private ServletConfig _config;

    /* ------------------------------------------------------------ */
    public void destroy()
    {
        try
        {
            Seti seti= (Seti)_config.getServletContext().getAttribute(Seti.SETI_ATTRIBUTE);
            if (seti!=null)
                seti.stop();
        }
        catch(Exception e)
        {
            _config.getServletContext().log("destroy",e);
        }
    }

    /* ------------------------------------------------------------ */
    public ServletConfig getServletConfig()
    {
        return _config;
    }

    /* ------------------------------------------------------------ */
    public String getServletInfo()
    {
        return SetiServlet.class.toString();
    }

    /* ------------------------------------------------------------ */
    public void init(ServletConfig config) throws ServletException
    {
        _config=config;
        
        Oort oort = (Oort)config.getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
        if (oort==null)
        {
            _config.getServletContext().log("No "+Oort.OORT_ATTRIBUTE+" initialized");
            throw new UnavailableException(Oort.OORT_ATTRIBUTE);
        }

        String shard=_config.getInitParameter(Seti.SETI_SHARD);
        
        Seti seti= new Seti(oort,shard);
        _config.getServletContext().setAttribute(Seti.SETI_ATTRIBUTE,seti);

        try
        {
            seti.start();
        }
        catch(Exception e)
        {
            throw new ServletException(e);
        }
        
    }

    /* ------------------------------------------------------------ */
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        HttpServletResponse response = (HttpServletResponse)res;
        response.sendError(503);        
    }
}
