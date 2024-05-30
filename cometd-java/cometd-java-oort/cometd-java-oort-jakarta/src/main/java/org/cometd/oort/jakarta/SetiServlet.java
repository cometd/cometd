/*
 * Copyright (c) 2008 the original author or authors.
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

package org.cometd.oort.jakarta;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;

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
    public static final String SETI_CONTEXT_ATTRIBUTE_NAME_PARAM = "setiContextAttributeName";

    private boolean exported;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        String oortContextAttributeName = getOortContextAttributeName();
        ServletContext servletContext = config.getServletContext();
        Oort oort = (Oort)servletContext.getAttribute(oortContextAttributeName);
        if (oort == null) {
            throw new UnavailableException("Missing " + oortContextAttributeName + " attribute");
        }

        try {
            String setiContextAttributeName = getSetiContextAttributeName();
            Seti seti = (Seti)servletContext.getAttribute(setiContextAttributeName);
            if (seti == null) {
                exported = true;
                seti = newSeti(oort);
            }

            seti.start();

            if (exported) {
                servletContext.setAttribute(setiContextAttributeName, seti);
            }
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
            String setiContextAttributeName = getSetiContextAttributeName();
            ServletContext servletContext = getServletConfig().getServletContext();
            Seti seti = (Seti)servletContext.getAttribute(setiContextAttributeName);
            if (seti != null) {
                seti.stop();
            }
            if (exported) {
                servletContext.removeAttribute(setiContextAttributeName);
            }
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }

    private String getOortContextAttributeName() {
        String name = getServletConfig().getInitParameter(OortConfigServlet.OORT_CONTEXT_ATTRIBUTE_NAME_PARAM);
        return name != null ? name : Oort.OORT_ATTRIBUTE;
    }

    private String getSetiContextAttributeName() {
        String name = getServletConfig().getInitParameter(SETI_CONTEXT_ATTRIBUTE_NAME_PARAM);
        return name != null ? name : Seti.SETI_ATTRIBUTE;
    }
}
