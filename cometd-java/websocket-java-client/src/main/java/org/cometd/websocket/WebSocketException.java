package org.cometd.websocket;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketException extends RuntimeException
{
    public WebSocketException()
    {
    }

    public WebSocketException(String message)
    {
        super(message);
    }

    public WebSocketException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public WebSocketException(Throwable cause)
    {
        super(cause);
    }
}
