package org.cometd.client.transport;

import java.net.ProtocolException;

public class ServerProtocolException extends ProtocolException {

    private static final long serialVersionUID = 3501190730626463124L;
    
    private int statusCode;
    private boolean webSocketUpgradeFailure;
    
    public ServerProtocolException(String message, int statusCode, boolean webSocketUpgradeFailure) 
    {
        super(message + (statusCode >= 0 ? " HTTP status: " + statusCode : "") + (webSocketUpgradeFailure ? " (WebSocket upgrade failed)" : ""));
        this.statusCode = statusCode;
        this.webSocketUpgradeFailure = webSocketUpgradeFailure;
    }

    public int getStatusCode() 
    {
        return this.statusCode;
    }
    
    public boolean isWebSocketUpgradeFailure() 
    {
        return this.webSocketUpgradeFailure;
    }
}