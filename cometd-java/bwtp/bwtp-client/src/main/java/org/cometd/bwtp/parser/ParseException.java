package org.cometd.bwtp.parser;

/**
 * @version $Revision$ $Date$
 */
public class ParseException extends RuntimeException
{
    public ParseException()
    {
    }

    public ParseException(String message)
    {
        super(message);
    }

    public ParseException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public ParseException(Throwable cause)
    {
        super(cause);
    }
}
