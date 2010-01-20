package org.cometd.client.transport;

/**
 * @version $Revision$ $Date$
 */
public class TransportException extends RuntimeException
{
    public TransportException()
    {
    }

    public TransportException(String message)
    {
        super(message);
    }

    public TransportException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public TransportException(Throwable cause)
    {
        super(cause);
    }
}
