package org.cometd.bwtp.parser;

import java.nio.ByteBuffer;

import org.cometd.bwtp.BWTPHeaderFrame;
import org.cometd.bwtp.BWTPMessageFrame;

/**
 * @version $Revision$ $Date$
 */
public interface BWTPParser
{
    void addListener(Listener listener);

    void removeListener(Listener listener);

    void parse(ByteBuffer buffer);

    interface Listener
    {
        void onHeaderFrame(BWTPHeaderFrame frame);

        void onMessageFrame(BWTPMessageFrame frame);

        public static class Adapter implements Listener
        {
            public void onHeaderFrame(BWTPHeaderFrame frame)
            {
            }

            public void onMessageFrame(BWTPMessageFrame frame)
            {
            }
        }
    }
}
