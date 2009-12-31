package org.cometd.bwtp;

import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public interface BWTPListener
{
    /**
     * <p>Callback invoked to allow customization of the OPENED frame.</p>
     * @param frame the OPEN frame sent by the client
     * @return the headers to be included in the OPENED frame
     */
    Map<String, String> onOpen(BWTPHeaderFrame frame);

    void onHeader(BWTPChannel channel, BWTPHeaderFrame frame);

    void onMessage(BWTPChannel channel, BWTPMessageFrame frame);

    Map<String, String> onClose(BWTPChannel channel, BWTPHeaderFrame frame);

    public static class Adapter implements BWTPListener
    {
        public Map<String, String> onOpen(BWTPHeaderFrame frame)
        {
            return null;
        }

        public void onHeader(BWTPChannel channel, BWTPHeaderFrame frame)
        {
        }

        public void onMessage(BWTPChannel channel, BWTPMessageFrame frame)
        {
        }

        public Map<String, String> onClose(BWTPChannel channel, BWTPHeaderFrame frame)
        {
            return null;
        }
    }
}
