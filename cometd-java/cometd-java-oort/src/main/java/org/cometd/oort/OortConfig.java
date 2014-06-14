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

import java.util.Properties;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.common.JSONContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class OortConfig {
	
	private final Logger logger = LoggerFactory.getLogger(OortConfig.class);
	
    public static final String OORT_URL_PARAM = "oort.url";
    public static final String OORT_CLIENT_DEBUG_PARAM = "clientDebug";
    public static final String OORT_SECRET_PARAM = "oort.secret";
    public static final String OORT_CHANNELS_PARAM = "oort.channels";
    public static final String OORT_ENABLE_ACK_EXTENSION_PARAM = "enableAckExtension";
    public static final String OORT_JSON_CONTEXT_PARAM = "jsonContext";

    protected final Oort oort;
    
	public OortConfig(Properties properties, BayeuxServer bayeux) throws OortConfigException {
        String url = properties.getProperty(OORT_URL_PARAM);
        if (url == null) {
        	throw new OortConfigException("OORT will not be enabled. Missing " + OORT_URL_PARAM + " init parameter");
        }
        	
        try
        {
        	oort = new Oort(bayeux, url);

            boolean clientDebug = Boolean.parseBoolean(properties.getProperty(OORT_CLIENT_DEBUG_PARAM));
            oort.setClientDebugEnabled(clientDebug);

            String secret = properties.getProperty(OORT_SECRET_PARAM, null);
            if (secret != null)
                oort.setSecret(secret);

            boolean enableAckExtension = Boolean.parseBoolean(properties.getProperty(OORT_ENABLE_ACK_EXTENSION_PARAM));
            oort.setAckExtensionEnabled(enableAckExtension);

            String jsonContext = properties.getProperty(OORT_JSON_CONTEXT_PARAM);
            if (jsonContext != null && !jsonContext.equals(""))
                oort.setJSONContextClient((JSONContext.Client)getClass().getClassLoader().loadClass(jsonContext).newInstance());

            oort.start();

            String channels = properties.getProperty(OORT_CHANNELS_PARAM);
            if (channels != null && !channels.equals(""))
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
            throw new OortConfigException(x);
        }
	}
	
	public Oort getOort() {
		return oort;
	}

	public abstract void destroyCloud() throws OortConfigException;
}
