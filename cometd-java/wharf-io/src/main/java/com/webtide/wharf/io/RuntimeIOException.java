package com.webtide.wharf.io;

/**
 * @version $Revision$ $Date$
 */
public class RuntimeIOException extends RuntimeException
{
    public RuntimeIOException()
    {
    }

    public RuntimeIOException(String message)
    {
        super(message);
    }

    public RuntimeIOException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public RuntimeIOException(Throwable cause)
    {
        super(cause);
    }
}
