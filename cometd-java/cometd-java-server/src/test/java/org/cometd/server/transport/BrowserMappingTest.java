package org.cometd.server.transport;

import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.server.AbstractBayeuxServerTest;
import org.cometd.server.BayeuxServerImpl;

/**
 * @version $Revision$ $Date$
 */
public class BrowserMappingTest extends AbstractBayeuxServerTest
{
    private BayeuxServerImpl bayeux;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
    }

    /*
    public void testBayeuxBrowserMapping() throws Exception
    {
        LongPollingTransport transport = new JSONTransport(bayeux);

        String browserId = "browser1";
        assertTrue(transport.addBrowserSession(browserId, "client1"));
        AtomicInteger browserClients = transport.getBrowserSessions(browserId);
        assertNotNull(browserClients);
        assertEquals(1, browserClients.get());

        assertFalse(transport.addBrowserSession(browserId, "client2"));
        browserClients = transport.getBrowserSessions(browserId);
        assertNotNull(browserClients);
        assertEquals(2, browserClients.get());

        assertFalse(transport.removeBrowserSession(browserId, "client1"));
        browserClients = transport.getBrowserSessions(browserId);
        assertNotNull(browserClients);
        assertEquals(1, browserClients.get());

        assertTrue(transport.removeBrowserSession(browserId, "client2"));
        browserClients = transport.getBrowserSessions(browserId);
        assertNull(browserClients);
    }
    */
}
