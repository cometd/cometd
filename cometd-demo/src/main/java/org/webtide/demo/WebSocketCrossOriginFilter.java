package org.webtide.demo;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.servlets.CrossOriginFilter;

/**
 * TODO: this filter is only needed for Jetty versions < 7.1.4
 * TODO: remove when CometD 2 is updated to use Jetty 7.1.4+
 * @version $Revision$ $Date$
 */
public class WebSocketCrossOriginFilter extends CrossOriginFilter
{
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (isEnabled((HttpServletRequest)request))
        {
            super.doFilter(request, response, chain);
        }
        else
        {
            chain.doFilter(request, response);
        }
    }

    protected boolean isEnabled(HttpServletRequest request)
    {
        // WebSocket clients such as Chrome 5 implement a version of the WebSocket
        // protocol that does not accept extra response headers on the upgrade response
        if ("Upgrade".equalsIgnoreCase(request.getHeader("Connection")) &&
            "WebSocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            return false;
        }
        return true;
    }
}
