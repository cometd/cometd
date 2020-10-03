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

import jakarta.servlet.ServletConfig;
import org.cometd.bayeux.server.BayeuxServer;

/**
 * <p>This servlet initializes and configures an instance of the {@link Oort}
 * CometD cluster manager with a static list of other Oort comet URLs.</p>
 * <p>This servlet must be initialized after an instance the CometD servlet
 * that creates the {@link BayeuxServer} instance used by {@link Oort}.</p>
 * <p>This servlet inherits from {@link OortConfigServlet} init parameters used
 * to configure the Oort instance, and adds the following init parameter:</p>
 * <ul>
 * <li>{@code oort.cloud}, a comma separated list of the {@code oort.url}s
 * of other known oort CometD cluster managers</li>
 * </ul>
 *
 * @see OortConfigServlet
 * @see OortMulticastConfigServlet
 */
public class OortStaticConfigServlet extends OortConfigServlet {
    public final static String OORT_CLOUD_PARAM = "oort.cloud";

    @Override
    protected void configureCloud(ServletConfig config, Oort oort) throws Exception {
        String cloud = config.getInitParameter(OORT_CLOUD_PARAM);
        if (cloud != null && cloud.length() > 0) {
            String[] urls = cloud.split(",");
            for (String comet : urls) {
                comet = comet.trim();
                if (comet.length() > 0) {
                    OortComet oortComet = oort.observeComet(comet);
                    if (oortComet == null) {
                        throw new IllegalArgumentException("Invalid value for " + OORT_CLOUD_PARAM);
                    }
                }
            }
        }
    }
}
