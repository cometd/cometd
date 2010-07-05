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

    public void testBayeuxBrowserMapping() throws Exception
    {
        LongPollingTransport transport = new JSONTransport(bayeux);

        String browserId = "browser1";
        assertTrue(transport.incBrowserId(browserId));
        assertFalse(transport.incBrowserId(browserId));
        transport.decBrowserId(browserId);
        assertTrue(transport.incBrowserId(browserId));
        transport.decBrowserId(browserId);
    }
}
