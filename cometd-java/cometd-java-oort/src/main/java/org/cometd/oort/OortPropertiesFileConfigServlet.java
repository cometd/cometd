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
import java.io.InputStream;
import java.util.Properties;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.common.JSONContext;

/**
 * <p>This servlet initializes and configures an instance of the {@link Oort}
 * CometD cluster manager with a static list of other Oort comet URLs.</p>
 * <p>This servlet must be initialized after an instance the CometD servlet
 * that creates the {@link BayeuxServer} instance used by {@link Oort}.</p>
 * <p>This servlet inherits from {@link OortConfigServlet} init parameters used
 * to configure the Oort instance, and adds the following init parameter:</p>
 * <ul>
 * <li><code>oort.cloud</code>, a comma separated list of the <code>oort.url</code>s
 * of other known oort CometD cluster managers</li>
 * </ul>
 *
 * @see OortConfigServlet
 * @see OortMulticastConfigServlet
 */
public class OortPropertiesFileConfigServlet implements Servlet
{

	public final static String OORT_PROPERTIES_FILENAME_PARAM = "oort.properties.file";
    public static final String OORT_URL_PARAM = "oort.url";
    public static final String OORT_SECRET_PARAM = "oort.secret";
    public static final String OORT_CHANNELS_PARAM = "oort.channels";
    public static final String OORT_ENABLE_ACK_EXTENSION_PARAM = "enableAckExtension";
    public static final String OORT_JSON_CONTEXT_PARAM = "jsonContext";
    public final static String OORT_PEER_DISCOVERY_PARAM = "oort.peer_discovery";
    
    private ServletConfig _config;
    private Properties _properties = new Properties();
    private OortConfig _oortConfig;
    
    @Override
    public void init(ServletConfig config) throws ServletException
    {
    	_config = config;

        String propertiesFile = _config.getInitParameter(OORT_PROPERTIES_FILENAME_PARAM);
        if (propertiesFile == null || propertiesFile.equals(""))
            throw new UnavailableException("Missing " + OORT_PROPERTIES_FILENAME_PARAM + " init parameter");

    	InputStream input = null;
    	try { 
    		input = this.getClass().getClassLoader().getResourceAsStream(propertiesFile);
    		if(input != null){
    			_properties.load(input);
    		} 
    	} catch (Exception e) {
            throw new ServletException(e);
        } finally{
        	if(input != null){
	        	try {
					input.close();
				} catch (IOException e) {
				}
        	}
        }

        BayeuxServer bayeux = (BayeuxServer)config.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        if (bayeux == null)
            throw new UnavailableException("Missing " + BayeuxServer.ATTRIBUTE + " attribute");

        String url = _properties.getProperty(OORT_URL_PARAM);
        if (url == null)
            throw new UnavailableException("Missing " + OORT_URL_PARAM + " init parameter");

        try
        {
        	Oort oort = new Oort(bayeux, url);

            String secret = _properties.getProperty(OORT_SECRET_PARAM, null);
            if (secret != null)
                oort.setSecret(secret);

            boolean enableAckExtension = Boolean.parseBoolean(_properties.getProperty(OORT_ENABLE_ACK_EXTENSION_PARAM));
            oort.setAckExtensionEnabled(enableAckExtension);

            String jsonContext = _properties.getProperty(OORT_JSON_CONTEXT_PARAM);
            if (jsonContext != null)
                oort.setJSONContextClient((JSONContext.Client)getClass().getClassLoader().loadClass(jsonContext).newInstance());

            oort.start();
            _config.getServletContext().setAttribute(Oort.OORT_ATTRIBUTE, oort);

            String peerDiscovery = _properties.getProperty(OORT_PEER_DISCOVERY_PARAM, null);
            if (peerDiscovery == null)
            	throw new UnavailableException("Missing " + OORT_PEER_DISCOVERY_PARAM + " init parameter");
            
            _oortConfig = OortConfigFactory.createOortConfigurator(peerDiscovery);
            _oortConfig.configureCloud(_properties, oort);

            String channels = _properties.getProperty(OORT_CHANNELS_PARAM);
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

	@Override
	public void destroy() {
        try
        {
        	_oortConfig.destroyCloud();
            Oort oort = (Oort)_config.getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
            if (oort != null)
                oort.stop();
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
        finally
        {
            _config.getServletContext().removeAttribute(Oort.OORT_ATTRIBUTE);
        }
	}

    public ServletConfig getServletConfig()
    {
        return _config;
    }

    public String getServletInfo()
    {
        return OortConfigServlet.class.toString();
    }

	@Override
	public void service(ServletRequest req , ServletResponse res)
			throws ServletException, IOException {
        HttpServletResponse response = (HttpServletResponse) res;
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
	}
}
