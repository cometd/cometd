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
import org.cometd.client.BayeuxClient;

public class OortStaticConfig extends OortConfig {

	public final static String OORT_CLOUD_PARAM = "oort.cloud";

	public OortStaticConfig(Properties properties, BayeuxServer bayeux)
			throws OortConfigException {
		super(properties, bayeux);

		String cloud = properties.getProperty(OORT_CLOUD_PARAM);
        if (cloud != null && cloud.length() > 0)
        {
            String[] urls = cloud.split(",");
            for (String comet : urls)
            {
                comet = comet.trim();
                if (comet.length() > 0)
                {
                    OortComet oortComet = oort.observeComet(comet);
                    if (oortComet == null)
                        throw new IllegalArgumentException("Invalid value for " + OORT_CLOUD_PARAM);
                    oortComet.waitFor(1000, BayeuxClient.State.CONNECTED, BayeuxClient.State.DISCONNECTED);
                }
            }
        }
	}

	@Override
	public void destroyCloud() {}
}
