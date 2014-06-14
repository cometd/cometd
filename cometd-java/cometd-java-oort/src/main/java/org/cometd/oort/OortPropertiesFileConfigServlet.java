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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private final Logger logger = LoggerFactory.getLogger(OortPropertiesFileConfigServlet.class);
	
	public final static String OORT_PROPERTIES_FILENAME_PARAM = "oort.properties.file";
    
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
    	} catch (Throwable e) {
    		logger.info("Error reading OORT properties file. OORT will not be enabled: " + e.getMessage());
    		return;
        } finally{
        	if(input != null){
	        	try {
					input.close();
				} catch (IOException e) {
				}
        	}
        }

        BayeuxServer bayeux = (BayeuxServer)config.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        if (bayeux == null) {
        	logger.info("OORT will not be enabled. Missing " + BayeuxServer.ATTRIBUTE + " attribute");
        	return;
        }
        	
        try
        {
            _oortConfig = OortConfigFactory.createOortConfigurator(_properties, bayeux);
            if(_oortConfig == null) {
        		logger.debug("OortConfig is null. Oort is disabled.");
            	return;
            }
            _config.getServletContext().setAttribute(Oort.OORT_ATTRIBUTE, _oortConfig.getOort());
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
        	if(_oortConfig != null) {
        		_oortConfig.destroyCloud();        		
        	}
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
        return OortPropertiesFileConfigServlet.class.toString();
    }

	@Override
	public void service(ServletRequest req , ServletResponse res)
			throws ServletException, IOException {
        HttpServletResponse response = (HttpServletResponse) res;
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
	}
}
