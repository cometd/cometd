package org.cometd.oort;

import javax.servlet.ServletConfig;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;

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
public class OortStaticConfigServlet extends OortConfigServlet
{
    public final static String OORT_CLOUD_PARAM = "oort.cloud";

    @Override
    protected void configureCloud(ServletConfig config, Oort oort) throws Exception
    {
        String cloud = config.getInitParameter(OORT_CLOUD_PARAM);
        if (cloud != null && cloud.length() > 0)
        {
            String[] urls = cloud.split(",");
            for (String comet : urls)
            {
                comet = comet.trim();
                if (comet.length() > 0)
                {
                    OortComet oortComet = oort.observeComet(comet);
                    oortComet.waitFor(1000, BayeuxClient.State.CONNECTED);
                }
            }
        }
    }
}
