package org.cometd.bayeux.server;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Collection;
import java.util.List;

public interface BayeuxContext
{
    
    /**
     * @return The user Principal (if any)
     */
    Principal getUserPrincipal();

    /**
     * @return True if there is a known user and they are in the passed role.
     */
    boolean isUserInRole(String role);
    
    /**
     * @return the remote socket address
     */
    InetSocketAddress getRemoteAddress();

    /**
     * @return the local socket address
     */
    InetSocketAddress getLocalAddress();

    /**
     * @return The names of known headers in the current transport or null if no current
     */
    Collection<String> getHeaderNames();

    /**
     * @return The names of known paramters in the current transport or null if no current
     */
    Collection<String> getParameterNames();
    
    /**
     * Get a transport header.<p>
     * Get a header for any current transport mechanism (eg HTTP request). 
     * For transports like websocket, the header may be from the initial handshake.
     * @param name The name of the header
     * @return The header value or null if no current transport mechanism or no such header.
     */
    String getHeader(String name);
    
    /**
     * Get a multi valued transport header.<p>
     * Get a header for any current transport mechanism (eg HTTP request). 
     * For transports like websocket, the header may be from the initial handshake.
     * @param name The name of the header
     * @return The header value or null if no current transport mechanism or no such header.
     */
    List<String> getHeaderValues(String name);

    /**
     * Get a transport parameter.<p>
     * Get a parameter for any current transport mechanism (eg HTTP request). 
     * For transports like websocket, the parameter may be from the initial handshake.
     * @param name The name of the parameter
     * @return The parameter value or null if no current transport mechanism or no such parameter.
     */
    String getParameter(String name);

    /**
     * Get a multi valued transport parameter.<p>
     * Get a parameter for any current transport mechanism (eg HTTP request). 
     * For transports like websocket, the parameter may be from the initial handshake.
     * @param name The name of the parameter
     * @return The parameter value or null if no current transport mechanism or no such parameter.
     */
    List<String> getParameterValues(String name);
    
    /**
     * Get a transport cookie.<p>
     * Get a cookie for any current transport mechanism (eg HTTP request). 
     * For transports like websocket, the cookie may be from the initial handshake.
     * @param name The name of the cookie
     * @return The cookie value or null if no current transport mechanism or no such cookie.
     */
    String getCookie(String name);
    
    /**
     * Access the HTTP Session (if any) ID.
     * The {@link ServerSession#getId()} should be used in preference to the HTTP Session.
     * @return HTTP session ID or null
     */
    String getHttpSessionId();

    /**
     * Access the HTTP Session (if any) attribute names.
     * The {@link ServerSession#getAttributeNames()} should be used in preference to the HTTP Session.
     * @return HTTP session ID or null
     */
    Collection<String> getHttpSesionAttributeNames();

    /**
     * Access the HTTP Session (if any) attributes.
     * The {@link ServerSession#getAttribute(String)} should be used in preference to the HTTP Session.
     * @return HTTP session ID or null
     */
    Object getHttpSessionAttribute(String name);

    /**
     * Access the HTTP Session (if any) attributes.
     * The {@link ServerSession#setAttribute(String, Object)} should be used in preference to the HTTP Session.
     * @return HTTP session ID or null
     */
    void setHttpSessionAttribute(String name, Object value);

    /**
     * Invalidate the HTTP Session.
     * The {@link ServerSession#getId()} should be used in preference to the HTTP Session.
     * @return HTTP session ID
     */
    void invalidateHttpSession();
    
}
