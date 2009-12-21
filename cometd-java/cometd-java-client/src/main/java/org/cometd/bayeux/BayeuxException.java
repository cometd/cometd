package org.cometd.bayeux;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxException extends RuntimeException
{
    public BayeuxException()
    {
    }

    public BayeuxException(String message)
    {
        super(message);
    }

    public BayeuxException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public BayeuxException(Throwable cause)
    {
        super(cause);
    }
}
