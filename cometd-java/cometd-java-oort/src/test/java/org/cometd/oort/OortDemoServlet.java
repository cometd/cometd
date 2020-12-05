/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.ext.TimesyncExtension;

public class OortDemoServlet extends HttpServlet {
    private ServerAnnotationProcessor _processor;
    private OortChatService _service;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        ServletContext context = config.getServletContext();

        BayeuxServer bayeux = (BayeuxServer)context.getAttribute(BayeuxServer.ATTRIBUTE);
        Oort oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        Seti seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);

        _processor = new ServerAnnotationProcessor(bayeux, oort, seti);
        _service = new OortChatService();
        _processor.process(_service);

        bayeux.addExtension(new TimesyncExtension());
    }

    @Override
    public void destroy() {
        _processor.deprocess(_service);
    }
}
