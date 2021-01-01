/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;

/**
 * <p>This servlet initializes and configures and instance of the {@link Seti}
 * user mapper.</p>
 * <p>This servlet must be initialized after an instance the Oort servlet
 * that creates the {@link Oort} instance.</p>
 * <p>Override method {@link #newSeti(Oort)} to return a customized
 * instance of {@link Seti}.</p>
 *
 * @see OortMulticastConfigServlet
 */
public class SetiServlet extends HttpServlet {
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        ServletContext servletContext = config.getServletContext();
        Oort oort = (Oort)servletContext.getAttribute(Oort.OORT_ATTRIBUTE);
        if (oort == null) {
            throw new UnavailableException("Missing " + Oort.OORT_ATTRIBUTE + " attribute");
        }

        try {
            Seti seti = newSeti(oort);
            seti.start();
            servletContext.setAttribute(Seti.SETI_ATTRIBUTE, seti);
        } catch (Exception x) {
            throw new ServletException(x);
        }
    }

    protected Seti newSeti(Oort oort) {
        return new Seti(oort);
    }

    @Override
    public void destroy() {
        try {
            ServletContext servletContext = getServletConfig().getServletContext();
            Seti seti = (Seti)servletContext.getAttribute(Seti.SETI_ATTRIBUTE);
            servletContext.removeAttribute(Seti.SETI_ATTRIBUTE);
            if (seti != null) {
                seti.stop();
            }
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }
}
