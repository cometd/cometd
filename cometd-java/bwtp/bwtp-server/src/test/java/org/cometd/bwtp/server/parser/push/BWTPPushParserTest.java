package org.cometd.bwtp.server.parser.push;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bwtp.BWTPHeaderFrame;
import org.cometd.bwtp.BWTPMessageFrame;
import org.cometd.bwtp.parser.BWTPParser;
import org.junit.Assert;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class BWTPPushParserTest
{
    @Test
    public void testUpgrade() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        BWTPParser parser = new ServerBWTPPushParser()
        {
            @Override
            protected void upgradeRequest(String method, String uri, String version, Map<String, String> headers)
            {
                latch.countDown();
            }
        };

        StringBuilder builder = new StringBuilder();
        builder.append("GET / HTTP/1.1\r\n");
        builder.append("Host: localhost:8080\r\n");
        builder.append("Upgrade: BWTP/1.0\r\n");
        builder.append("Connection: upgrade\r\n");
        builder.append("\r\n");

        byte[] bytes = builder.toString().getBytes("UTF-8");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        parser.parse(buffer);

        Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testOpen() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        BWTPParser parser = new ServerBWTPPushParser()
        {
            @Override
            protected void upgradeRequest(String method, String uri, String version, Map<String, String> headers)
            {
            }
        };
        parser.addListener(new BWTPParser.Listener()
        {
            public void onHeaderFrame(BWTPHeaderFrame frame)
            {
                latch.countDown();
            }

            public void onMessageFrame(BWTPMessageFrame frame)
            {
            }
        });

        StringBuilder builder = new StringBuilder();
        builder.append("GET / HTTP/1.1\r\n");
        builder.append("Host: localhost:8080\r\n");
        builder.append("Upgrade: BWTP/1.0\r\n");
        builder.append("Connection: upgrade\r\n");
        builder.append("\r\n");
        builder.append("BWH 0 68 OPEN /bwtp\r\n");
        builder.append("Content-Type: application/json;charset=UTF-8\r\n");
        builder.append("Content-Language: en\r\n");

        byte[] bytes = builder.toString().getBytes("UTF-8");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        parser.parse(buffer);

        Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }
}
