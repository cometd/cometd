/*
 * Copyright (c) 2008-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.oort;

import java.io.IOException;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.cometd.annotation.ServerAnnotationProcessor;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.ext.TimesyncExtension;

public class OortDemoServlet implements Servlet
{
    private ServletConfig _config;
    private ServerAnnotationProcessor _processor;
    private OortChatService _service;

    public String getServletInfo()
    {
        return this.getClass().toString();
    }

    public ServletConfig getServletConfig()
    {
        return _config;
    }

    public void init(ServletConfig config) throws ServletException
    {
        _config = config;

        ServletContext context = config.getServletContext();

        BayeuxServer bayeux = (BayeuxServer)context.getAttribute(BayeuxServer.ATTRIBUTE);
        Oort oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        Seti seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);

        _processor = new ServerAnnotationProcessor(bayeux, oort, seti);
        _service = new OortChatService();
        _processor.process(_service);

        bayeux.addExtension(new TimesyncExtension());
    }

    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        throw new UnsupportedOperationException();
    }

    public void destroy()
    {
        _processor.deprocess(_service);
    }
}
