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

import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.common.JSONContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This servlet serves as a base class for initializing and configuring an
 * instance of the {@link Oort} CometD cluster manager.</p>
 * <p>The following servlet init parameters are used to configure the Oort instance:</p>
 * <ul>
 * <li>{@code oort.url}, the absolute public URL to the CometD servlet</li>
 * <li>{@code oort.secret}, the pre-shared secret that Oort servers use to authenticate
 * connections from other Oort comets</li>
 * <li>{@code oort.channels}, a comma separated list of channels that
 * will be passed to {@link Oort#observeChannel(String)}</li>
 * <li>{@code clientDebug}, a boolean that enables debugging of the
 * clients connected to other oort cluster managers</li>
 * </ul>
 * <p>Override method {@link #newOort(BayeuxServer, String)} to return a customized
 * instance of {@link Oort}.</p>
 *
 * @see SetiServlet
 */
public abstract class OortConfigServlet extends HttpServlet {
    public static final String OORT_URL_PARAM = "oort.url";
    public static final String OORT_SECRET_PARAM = "oort.secret";
    public static final String OORT_CHANNELS_PARAM = "oort.channels";
    public static final String OORT_ENABLE_ACK_EXTENSION_PARAM = "enableAckExtension";
    public static final String OORT_ENABLE_BINARY_EXTENSION_PARAM = "enableBinaryExtension";
    public static final String OORT_JSON_CONTEXT_PARAM = "jsonContext";
    private static final Logger LOGGER = LoggerFactory.getLogger(OortConfigServlet.class);

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        ServletContext servletContext = config.getServletContext();
        BayeuxServer bayeux = (BayeuxServer)servletContext.getAttribute(BayeuxServer.ATTRIBUTE);
        if (bayeux == null) {
            throw new UnavailableException("Missing " + BayeuxServer.ATTRIBUTE + " attribute");
        }

        String url = provideOortURL();
        if (url == null) {
            throw new UnavailableException("Missing " + OORT_URL_PARAM + " init parameter");
        }

        try {
            Oort oort = newOort(bayeux, url);
            configureOort(config, oort);

            oort.start();
            servletContext.setAttribute(Oort.OORT_ATTRIBUTE, oort);

            new Thread(new Starter(config, oort)).start();
        } catch (Exception x) {
            throw new ServletException(x);
        }
    }

    /**
     * <p>Retrieves the {@code oort.url} parameter from this servlet init parameters.</p>
     * <p>Subclasses can override this method to compute the {@code oort.url} parameter
     * dynamically, for example by retrieving the IP address of the host.</p>
     *
     * @return the {@code oort.url} parameter
     */
    protected String provideOortURL() {
        return getServletConfig().getInitParameter(OORT_URL_PARAM);
    }

    /**
     * <p>Creates and returns a new Oort instance.</p>
     *
     * @param bayeux the BayeuxServer instance to which the Oort instance should be associated to
     * @param url    the {@code oort.url} of the Oort instance
     * @return a new Oort instance
     */
    protected Oort newOort(BayeuxServer bayeux, String url) {
        return new Oort(bayeux, url);
    }

    /**
     * <p>Configures the Oort instance with servlet init parameters.</p>
     *
     * @param config the Servlet configuration
     * @param oort the Oort instance to configure
     * @throws Exception if the Oort instance cannot be configured
     */
    protected void configureOort(ServletConfig config, Oort oort) throws Exception {
        String secret = config.getInitParameter(OORT_SECRET_PARAM);
        if (secret != null) {
            oort.setSecret(secret);
        }

        String enableAckExtension = config.getInitParameter(OORT_ENABLE_ACK_EXTENSION_PARAM);
        if (enableAckExtension == null) {
            enableAckExtension = "true";
        }
        oort.setAckExtensionEnabled(Boolean.parseBoolean(enableAckExtension));

        String enableBinaryExtension = config.getInitParameter(OORT_ENABLE_BINARY_EXTENSION_PARAM);
        if (enableBinaryExtension == null) {
            enableBinaryExtension = "true";
        }
        oort.setBinaryExtensionEnabled(Boolean.parseBoolean(enableBinaryExtension));

        String jsonContext = config.getInitParameter(OORT_JSON_CONTEXT_PARAM);
        if (jsonContext != null) {
            Class<?> klass = getClass().getClassLoader().loadClass(jsonContext);
            oort.setJSONContextClient((JSONContext.Client)klass.getConstructor().newInstance());
        }
    }

    /**
     * <p>Configures the Oort cloud by establishing connections with other Oort comets.</p>
     * <p>Subclasses implement their own strategy to discover and link with other comets.</p>
     *
     * @param config the servlet configuration to read parameters from
     * @param oort   the Oort instance associated with this configuration servlet
     * @throws Exception if the cloud configuration fails
     */
    protected abstract void configureCloud(ServletConfig config, Oort oort) throws Exception;

    @Override
    public void destroy() {
        try {
            ServletContext servletContext = getServletConfig().getServletContext();
            Oort oort = (Oort)servletContext.getAttribute(Oort.OORT_ATTRIBUTE);
            servletContext.removeAttribute(Oort.OORT_ATTRIBUTE);
            if (oort != null) {
                oort.stop();
            }
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }

    private class Starter implements Runnable {
        private final ServletConfig config;
        private final Oort oort;
        private final OortComet oortComet;

        private Starter(ServletConfig config, Oort oort) {
            this.config = config;
            this.oort = oort;
            this.oortComet = oort.newOortComet(oort.getURL());
            this.oortComet.setOption(BayeuxClient.MAX_BACKOFF_OPTION, 1000L);
        }

        @Override
        public void run() {
            // Connect to myself until success. If the handshake fails,
            // the normal BayeuxClient retry mechanism will kick-in.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Connecting to self: {}", oort);
            }
            Map<String, Object> fields = oort.newOortHandshakeFields(oort.getURL(), null);
            oortComet.handshake(fields, message -> {
                // If the handshake fails but has an advice field, it means it
                // reached the server but was denied e.g. by a SecurityPolicy.
                Map<String, Object> advice = message.getAdvice();
                if (message.isSuccessful() || advice != null) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Connected to self: {}", oort);
                    }
                    oortComet.disconnect();
                    joinCloud();
                }
            });
        }

        private void joinCloud() {
            try {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Joining cloud: {}", oort);
                }

                configureCloud(config, oort);

                String channels = config.getInitParameter(OORT_CHANNELS_PARAM);
                if (channels != null) {
                    String[] patterns = channels.split(",");
                    for (String channel : patterns) {
                        channel = channel.trim();
                        if (channel.length() > 0) {
                            oort.observeChannel(channel);
                        }
                    }
                }
            } catch (Throwable x) {
                LOGGER.warn("Could not start Oort", x);
                destroy();
            }
        }
    }
}
