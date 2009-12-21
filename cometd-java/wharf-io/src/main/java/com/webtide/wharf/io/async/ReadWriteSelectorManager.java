package com.webtide.wharf.io.async;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import com.webtide.wharf.io.RuntimeIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class ReadWriteSelectorManager implements SelectorManager
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Queue<Registration> registrations = new ConcurrentLinkedQueue<Registration>();
    private final Selector selector;

    public ReadWriteSelectorManager(Executor threadPool)
    {
        try
        {
            this.selector = Selector.open();
            threadPool.execute(new SelectorLoop());
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    public void close()
    {
        try
        {
            selector.close();
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    public void register(AsyncEndpoint endpoint, int operations, Listener listener)
    {
        registrations.add(new Registration(selector, endpoint, operations, listener));
        wakeup();
    }

    public void wakeup()
    {
        selector.wakeup();
    }

    protected void processRegistrations()
    {
        while (registrations.size() > 0)
        {
            Registration registration = registrations.poll();
            logger.debug("Processing registration {}", registration);
            try
            {
                registration.register();
            }
            catch (ClosedChannelException x)
            {
                logger.debug("Ignoring registration for closed listener {}", registration.listener);
            }
        }
    }

    protected void process(SelectionKey selectedKey) throws IOException
    {
        if (selectedKey.isReadable())
        {
            Listener listener = (Listener)selectedKey.attachment();
            listener.readReady();
        }
        else if (selectedKey.isWritable())
        {
            Listener listener = (Listener)selectedKey.attachment();
            listener.writeReady();
        }
    }

    private class Registration
    {
        private final Selector selector;
        private final AsyncEndpoint endpoint;
        private final int operations;
        private final Listener listener;

        private Registration(Selector selector, AsyncEndpoint endpoint, int operations, Listener listener)
        {
            this.selector = selector;
            this.endpoint = endpoint;
            this.operations = operations;
            this.listener = listener;
        }

        public void register() throws ClosedChannelException
        {
            endpoint.registerWith(selector, operations, listener);
        }
    }

    private class SelectorLoop implements Runnable
    {
        public void run()
        {
            try
            {
                logger.debug("Selector loop entered");

                while (selector.isOpen())
                {
                    try
                    {
                        processRegistrations();

                        logger.debug("Selector loop waiting on select");
                        int selected = selector.select();
                        logger.debug("Selector loop woken up from select, {} selected", selected);

                        // Closing the selector causes a wakeup, check if we have to exit
                        if (!selector.isOpen())
                            break;

                        if (selected > 0)
                        {
                            Set<SelectionKey> selectedKeys = selector.selectedKeys();
                            logger.debug("Selector loop selected keys {}", selectedKeys);
                            for (Iterator<SelectionKey> iterator = selectedKeys.iterator(); iterator.hasNext();)
                            {
                                SelectionKey selectedKey = iterator.next();
                                iterator.remove();

                                if (!selectedKey.isValid())
                                    continue;

                                process(selectedKey);
                            }
                        }
                    }
                    catch (ClosedSelectorException x)
                    {
                        break;
                    }
                    catch (IOException x)
                    {
                        close();
                        throw new RuntimeIOException(x);
                    }
                }
            }
            finally
            {
                logger.info("Selector loop exited");
            }
        }
    }
}
