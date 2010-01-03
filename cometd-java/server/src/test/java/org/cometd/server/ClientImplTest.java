package org.cometd.server;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Random;

import junit.framework.TestCase;
import org.cometd.Client;
import org.cometd.ClientListener;
import org.cometd.DeliverListener;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.QueueListener;
import org.cometd.RemoveListener;

/**
 * @author athena
 *
 */
public class ClientImplTest extends TestCase
{
    protected StandaloneClient _client;
    protected Message _message;

    protected void setUp() throws Exception
    {
        _client = new StandaloneClient();
        _message = new MessageImpl();
    }

    public void testThrowingListeners() throws Exception
    {
        Message message = m("count", "1");
        ClientListener listener1 = new ThrowingMultiListener();
        MultiListener multiListener = new MultiListener();
        _client.setMaxQueue(1);
        _client.addListener(listener1);
        _client.addListener(multiListener);

        _client.deliver(message);
        assertTrue(multiListener.messageListenerCalled());
        multiListener.reset();

        _client.deliver(message);
        assertTrue(multiListener.queueListenerCalled());
        assertTrue(multiListener.messageListenerCalled());
        multiListener.reset();

        _client.doDeliverListeners();
        assertTrue(multiListener.deliverListenerCalled());
        multiListener.reset();

        _client.remove(false);
        assertTrue(multiListener.removeListenerCalled());
        multiListener.reset();
    }

    /*
     * QueueListener examples
     */
    public void testDeleteWhenFullQueue() throws Exception
    {
        Message delete =  m("delete", "a");
        Message keep = m("keep", "b");
        Message add = m("add", "c");

        _client.setMaxQueue(2);
        _client.addListener(new DeleteWhenFullQueueListener());

        _client.deliver(delete);
        _client.deliver(keep);
        _client.deliver(add);

        assertEquals(resultsList(keep,add), _client.takeMessages());
    }

    public void testDiscardNewMessageQueue() throws Exception
    {
        Message keep1 = m("keep1", "a");
        Message keep2 = m("keep2", "b");
        Message discard = m("discard", "c");

        _client.setMaxQueue(2);
        _client.addListener(new DiscardNewMessageQueueListener());

        _client.deliver(keep1);
        _client.deliver(keep2);
        _client.deliver(discard);

        assertEquals(resultsList(keep1, keep2), _client.takeMessages());
    }

    public void testModifyExistingMessagesQueue() throws Exception
    {
        Message keep = m("keep", "a");
        Message delete = m("delete", "b");
        Message add = m("add", "c");

        _client.setMaxQueue(2);
        _client.addListener(new ModifyExistingMessagesQueueListener());

        _client.deliver(keep);
        _client.deliver(delete);
        _client.deliver(add);

        assertEquals(resultsList(keep, add), _client.takeMessages());
    }

    // TODO: improve this test?
    public void testTakeWhileQueueing() throws Exception
    {
        _client.setMaxQueue(2);
        _client.addListener(new DeleteWhenFullQueueListener());

        Message[] m = new Message[5];
        for (int i = 0; i < m.length; ++i)
        {
            m[i] = m(i + "", i+"");
        }

        (new Thread() {
            public void run()
            {
                while (true ) {
                    _client.getQueue().poll();
                    try
                    {
                        Thread.sleep(100);
                    }
                    catch (Exception e) {}
                }
            }
        }).start();

        for (Message message : m)
        {
            _client.deliver(message);
        }

        assertEquals(resultsList(m[m.length-2], m[m.length-1]),_client.takeMessages());
    }

    public void testId() throws Exception
    {
        AbstractBayeux bayeux = new BayeuxStub();
        //bayeux.setNodeId("nodeid");
        _client = new StandaloneClient(bayeux);
        bayeux.addClient(_client,null);

        // TODO
    }

    public void testMessageListener() throws Exception
    {
        _message.put("key","value");

        _client.addListener(new CheckMessageListener(_message));
        _client.addListener(new CheckMessageListener(_message));

        _client.deliver(_message);
    }

    public void testRemoveListener() throws Exception
    {
        // TODO
    }

    public void testDeliverListener() throws Exception
    {
        final boolean[] called = {false};
        _client.addListener(new DeliverListener()
        {
            public void deliver(Client client, Queue<Message> queue)
            {
                called[0]=true;
            }
        });

        Message ping = m("ping", "hello");
        _client.deliver(ping);
        assertFalse(called[0]);

        _client.doDeliverListeners();

        assertTrue(called[0]);
    }

/*  // TODO: make sure this works properly. I think there's a chance of a deadlock
    public void testTakeMessage() throws Exception
    {
        Message[] messages = {
                m("a", "apple"),
                m("b", "bapple"),
                m("c", "capple"),
                m("d", "dapple"),
                m("e", "eapple"),
                m("f", "fapple")
        };

        final CountDownLatch todo = new CountDownLatch(messages.length);

        // temporary variable to avoid error with abstract lists not implementing remove
        List<Message> messageList = new ArrayList<Message>();
        messageList.addAll(Arrays.asList(messages));
        _client.returnMessages(messageList);

        final ClientImpl threadClient = _client;
        for(int i = 0; i < _client.getMessages(); ++i)
        {
            final int x = i;
            (new Thread() {
                public void run()
                {
                    try
                    {
                        int sleep = 10;
                        Thread.sleep(sleep);
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }

                    Message message = threadClient.takeMessage();
                    todo.countDown();
                    // System.out.print(todo.getCount() + " -- ");
                    // System.out.println(x + " in thread client: " + message + "; "+threadClient.getMessages() + " left");


                }
            }).start();
        }

        todo.await(5, TimeUnit.SECONDS);
    }
*/

    private Message m(String key, String value)
    {
        Message message = new MessageImpl();
        message.put(key, value);
        return message;
    }

    private List<Message> resultsList(Message m1, Message m2)
    {
        return Arrays.asList(new Message[] {m1, m2});
    }

    class StandaloneClient extends ClientImpl
    {
        public StandaloneClient()
        {
            super(new BayeuxStub(), "standalone");
        }

        public StandaloneClient(AbstractBayeux bayeux)
        {
            super(bayeux, "standalone");
        }

        /*
         * Warn for methods that require a proper Bayeux object
         */
        @Override
        public void deliver(Client from, String toChannel, Object data, String id)
        {
            System.err.println("Method unsupported!");
        }

        public void deliver(Message message)
        {
            doDelivery(null, message);
        }

        @Override
        public void setBrowserId(String id)
        {
            System.err.println("Method unsupported!");
        }
    }

    /*
     * stub out and use to initialize a "standalone" client
     */
    static class BayeuxStub extends AbstractBayeux
    {
        public BayeuxStub()
        {
            try
            {
                _random=SecureRandom.getInstance("SHA1PRNG");
            }
            catch (Exception e)
            {
                _random=new Random();
            }
            // lacks the context hashcode from AbstractBayeux#initialize()
            _random.setSeed(_random.nextLong()^hashCode()^Runtime.getRuntime().freeMemory());
        }

        public ClientImpl newRemoteClient()
        {
            return null;
        }
    }

    static class DeleteWhenFullQueueListener implements QueueListener
    {
        public boolean queueMaxed(Client from, Client client, Message message)
        {
            client.getQueue().poll();
            return true;
        }

    }

    static class DiscardNewMessageQueueListener implements QueueListener
    {
        public boolean queueMaxed(Client from, Client client, Message message)
        {
            return false;
        }

    }

    static class ModifyExistingMessagesQueueListener implements QueueListener
    {
        public boolean queueMaxed(Client from, Client client, Message message)
        {
            Iterator<Message> queueIterator = client.getQueue().iterator();
            boolean removed = false;
            while(queueIterator.hasNext())
            {
                Message m = queueIterator.next();
                if(m.get("delete")!=null)
                {
                    queueIterator.remove();
                    removed=true;
                }
            }

            return removed;
        }
    }

    static class CheckMessageListener implements MessageListener
    {
        Message _message;
        public CheckMessageListener(Message message)
        {
            _message = message;
        }

        public void deliver(Client fromClient, Client toClient, Message msg)
        {
            assertEquals(_message, msg);
        }
    }

    private static class ThrowingMultiListener implements RemoveListener, QueueListener, MessageListener, DeliverListener
    {
        public void removed(String s, boolean b)
        {
            throw new RuntimeException();
        }

        public boolean queueMaxed(Client client, Client client1, Message message)
        {
            throw new RuntimeException();
        }

        public void deliver(Client client, Client client1, Message message)
        {
            throw new RuntimeException();
        }

        public void deliver(Client client, Queue<Message> messages)
        {
            throw new RuntimeException();
        }
    }

    private static class MultiListener implements RemoveListener, QueueListener, MessageListener, DeliverListener
    {
        private boolean removeCalled;
        private boolean queueCalled;
        private boolean messageCalled;
        private boolean deliverCalled;

        public void removed(String s, boolean b)
        {
            removeCalled = true;
        }

        public boolean queueMaxed(Client client, Client client1, Message message)
        {
            queueCalled = true;
            return true;
        }

        public void deliver(Client client, Client client1, Message message)
        {
            messageCalled = true;
        }

        public void deliver(Client client, Queue<Message> messages)
        {
            deliverCalled = true;
        }

        private boolean removeListenerCalled()
        {
            return removeCalled;
        }

        private boolean queueListenerCalled()
        {
            return queueCalled;
        }

        private boolean messageListenerCalled()
        {
            return messageCalled;
        }

        private boolean deliverListenerCalled()
        {
            return deliverCalled;
        }

        public void reset()
        {
            removeCalled = false;
            queueCalled = false;
            messageCalled = false;
            deliverCalled = false;
        }
    }
}
