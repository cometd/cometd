package org.cometd.server;

import java.util.List;

import org.cometd.Bayeux;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxBrowserMappingTest extends AbstractBayeuxServerTest
{
    private AbstractBayeux bayeux;

    @Override
    protected void customizeBayeux(Bayeux bayeux)
    {
        this.bayeux = (AbstractBayeux)bayeux;
    }

    public void testBayeuxBrowserMapping() throws Exception
    {
        String browserId = "browser1";
        bayeux.clientOnBrowser(browserId, "client1");
        List<String> browserClients = bayeux.clientsOnBrowser(browserId);
        assertNotNull(browserClients);
        assertEquals(1, browserClients.size());

        bayeux.clientOnBrowser(browserId, "client2");
        browserClients = bayeux.clientsOnBrowser(browserId);
        assertNotNull(browserClients);
        assertEquals(2, browserClients.size());

        bayeux.clientOffBrowser(browserId, "client1");
        browserClients = bayeux.clientsOnBrowser(browserId);
        assertNotNull(browserClients);
        assertEquals(1, browserClients.size());
        assertEquals("client2", browserClients.get(0));

        bayeux.clientOffBrowser(browserId, "client2");
        browserClients = bayeux.clientsOnBrowser(browserId);
        assertNull(browserClients);
    }
}
