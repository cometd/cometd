// ========================================================================
// Copyright 2007 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.server;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.Bayeux;
import org.cometd.DataFilter;
import org.cometd.Message;
import org.cometd.server.filter.JSONDataFilter;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;

/**
 * Cometd Filter Servlet implementing the {@link AbstractBayeux} protocol.
 *
 * The Servlet can be initialized with a json file mapping channels to
 * {@link DataFilter} definitions. The servlet init parameter "filters" should
 * point to a webapplication resource containing a JSON array of filter
 * definitions. For example:
 *
 * <pre>
 *  [
 *    {
 *      &quot;channels&quot;: &quot;/**&quot;,
 *      &quot;class&quot;   : &quot;org.cometd.server.filter.NoMarkupFilter&quot;,
 *      &quot;init&quot;    : {}
 *    }
 *  ]
 * </pre>
 *
 * The following init parameters can be used to configure the servlet:
 * <dl>
 * <dt>timeout</dt>
 * <dd>The server side poll timeout in milliseconds (default 250000). This is
 * how long the server will hold a reconnect request before responding.</dd>
 *
 * <dt>interval</dt>
 * <dd>The client side poll timeout in milliseconds (default 0). How long a
 * client will wait between reconnects</dd>
 *
 * <dt>maxInterval</dt>
 * <dd>The max client side poll timeout in milliseconds (default 10000). A
 * client will be removed if a connection is not received in this time.
 *
 * <dt>maxLazyLatency</dt>
 * <dd>The max time in ms(default 0) that a client with lazy messages will wait before
 * sending a response. If 0, then the client will wait until the next timeout or
 * non-lazy message.
 *
 * <dt>multiFrameInterval</dt>
 * <dd>the client side poll timeout if multiple connections are detected from
 * the same browser (default 1500).</dd>
 *
 * <dt>JSONCommented</dt>
 * <dd>If "true" then the server will accept JSON wrapped in a comment and will
 * generate JSON wrapped in a comment. This is a defence against Ajax Hijacking.
 * </dd>
 *
 * <dt>filters</dt>
 * <dd>the location of a JSON file describing {@link DataFilter} instances to be
 * installed</dd>
 *
 * <dt>requestAvailable</dt>
 * <dd>If true, the current request is made available via the
 * {@link AbstractBayeux#getCurrentRequest()} method</dd>
 *
 * <dt>logLevel</dt>
 * <dd>0=none, 1=info, 2=debug</dd>
 *
 * <dt>jsonDebug</dt>
 * <dd>If true, JSON complete json input will be kept for debug.</dd>
 *
 * <dt>channelIdCacheLimit</dt>
 * <dd>The limit of the {@link ChannelId} cache: -1 to disable caching, 0 for no limits,
 * any positive value to clear the cache once the limit has been reached</dd>
 *
 * <dt>refsThreshold</dt>
 * <dd>The number of message refs at which the a single message response will be
 * cached instead of being generated for every client delivered to. Done to
 * optimize a single message being sent to multiple clients.</dd>
 * </dl>
 *
 * @author gregw
 * @author aabeling: added JSONP transport
 *
 * @see {@link AbstractBayeux}
 * @see {@link ChannelId}
 */
public abstract class AbstractCometdServlet extends GenericServlet
{
    public static final String CLIENT_ATTR="org.cometd.server.client";
    public static final String TRANSPORT_ATTR="org.cometd.server.transport";
    public static final String MESSAGE_PARAM="message";
    public static final String TUNNEL_INIT_PARAM="tunnelInit";
    public static final String HTTP_CLIENT_ID="BAYEUX_HTTP_CLIENT";
    public final static String BROWSER_ID="BAYEUX_BROWSER";

    protected AbstractBayeux _bayeux;
    protected boolean _jsonDebug;

    public AbstractBayeux getBayeux()
    {
        return _bayeux;
    }

    protected abstract AbstractBayeux newBayeux();

    @Override
    public void init() throws ServletException
    {
        synchronized(AbstractCometdServlet.class)
        {
            _bayeux=(AbstractBayeux)getServletContext().getAttribute(Bayeux.ATTRIBUTE);
            if (_bayeux == null)
            {
                _bayeux=newBayeux();
            }
        }

        synchronized(_bayeux)
        {
            boolean was_initialized=_bayeux.isInitialized();
            _bayeux.initialize(getServletContext());

            if (!was_initialized)
            {
                String filters=getInitParameter("filters");
                if (filters != null)
                {
                    try
                    {
                        InputStream is=getServletContext().getResourceAsStream(filters);
                        if (is == null)
                            throw new FileNotFoundException(filters);

                        Object[] objects=(Object[])JSON.parse(new InputStreamReader(getServletContext().getResourceAsStream(filters),"utf-8"));
                        for (int i=0; objects != null && i < objects.length; i++)
                        {
                            Map<?,?> filter_def=(Map<?,?>)objects[i];

                            String fc=(String)filter_def.get("class");
                            if (fc != null)
                                Log.warn(filters + " file uses deprecated \"class\" name. Use \"filter\" instead");
                            else
                                fc=(String)filter_def.get("filter");
                            Class<?> c=Thread.currentThread().getContextClassLoader().loadClass(fc);
                            DataFilter filter=(DataFilter)c.newInstance();

                            if (filter instanceof JSONDataFilter)
                                ((JSONDataFilter)filter).init(filter_def.get("init"));

                            _bayeux.getChannel((String)filter_def.get("channels"),true).addDataFilter(filter);
                        }
                    }
                    catch(Exception e)
                    {
                        getServletContext().log("Could not parse: " + filters,e);
                        throw new ServletException(e);
                    }
                }

                String timeout=getInitParameter("timeout");
                if (timeout != null)
                    _bayeux.setTimeout(Long.parseLong(timeout));

                String maxInterval=getInitParameter("maxInterval");
                if (maxInterval != null)
                    _bayeux.setMaxInterval(Long.parseLong(maxInterval));

                String commentedJSON=getInitParameter("JSONCommented");
                _bayeux.setJSONCommented(commentedJSON != null && Boolean.parseBoolean(commentedJSON));

                String l=getInitParameter("logLevel");
                if (l != null && l.length() > 0)
                    _bayeux.setLogLevel(Integer.parseInt(l));

                String interval=getInitParameter("interval");
                if (interval != null)
                    _bayeux.setInterval(Long.parseLong(interval));

                String maxLazy=getInitParameter("maxLazyLatency");
                if (maxLazy != null)
                    _bayeux.setMaxLazyLatency(Integer.parseInt(maxLazy));

                String mfInterval=getInitParameter("multiFrameInterval");
                if (mfInterval != null)
                    _bayeux.setMultiFrameInterval(Integer.parseInt(mfInterval));

                String requestAvailable=getInitParameter("requestAvailable");
                _bayeux.setRequestAvailable(requestAvailable != null && Boolean.parseBoolean(requestAvailable));

                String async=getInitParameter("asyncDeliver");
                if (async != null)
                    getServletContext().log("asyncDeliver no longer supported");

                String jsonD=getInitParameter("jsonDebug");
                _jsonDebug=jsonD!=null && Boolean.parseBoolean(jsonD);

                String channelIdCacheLimit=getInitParameter("channelIdCacheLimit");
                if (channelIdCacheLimit != null)
                    _bayeux.setChannelIdCacheLimit(Integer.parseInt(channelIdCacheLimit));

                _bayeux.generateAdvice();

                if (_bayeux.isLogInfo())
                {
                    getServletContext().log("timeout=" + timeout);
                    getServletContext().log("interval=" + interval);
                    getServletContext().log("maxInterval=" + maxInterval);
                    getServletContext().log("multiFrameInterval=" + mfInterval);
                    getServletContext().log("filters=" + filters);
                }
            }
        }

        getServletContext().setAttribute(Bayeux.ATTRIBUTE,_bayeux);
    }

    protected abstract void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException;

    @Override
    public void service(ServletRequest req, ServletResponse resp) throws ServletException, IOException
    {
        HttpServletRequest request=(HttpServletRequest)req;
        HttpServletResponse response=(HttpServletResponse)resp;

        if (_bayeux.isRequestAvailable())
            _bayeux.setCurrentRequest(request);
        try
        {
            service(request,response);
        }
        finally
        {
            if (_bayeux.isRequestAvailable())
                _bayeux.setCurrentRequest(null);
        }
    }

    protected String findBrowserId(HttpServletRequest request)
    {
        Cookie[] cookies=request.getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (BROWSER_ID.equals(cookie.getName()))
                    return cookie.getValue();
            }
        }

        return null;
    }

    protected String setBrowserId(HttpServletRequest request, HttpServletResponse response)
    {
        String browser_id=Long.toHexString(request.getRemotePort()) + Long.toString(_bayeux.getRandom(),36) + Long.toString(System.currentTimeMillis(),36)
                + Long.toString(request.getRemotePort(),36);

        Cookie cookie=new Cookie(BROWSER_ID,browser_id);
        cookie.setPath("/");
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
        return browser_id;
    }

    private static Message[] __EMPTY_BATCH=new Message[0];

    protected Message[] getMessages(HttpServletRequest request) throws IOException, ServletException
    {
        String messageString=null;
        try
        {
            // Get message batches either as JSON body or as message parameters
            if (request.getContentType() != null && !request.getContentType().startsWith("application/x-www-form-urlencoded"))
            {
                if (_jsonDebug)
                {
                    messageString=IO.toString(request.getReader());
                    return _bayeux.parse(messageString);
                }
                return _bayeux.parse(request.getReader());
            }

            String[] batches=request.getParameterValues(MESSAGE_PARAM);

            if (batches == null || batches.length == 0)
                return __EMPTY_BATCH;

            if (batches.length == 1)
            {
                messageString=batches[0];
                return _bayeux.parse(messageString);
            }

            List<Message> messages=new ArrayList<Message>();
            for (String batch : batches)
            {
                if (batch == null)
                    continue;
                messageString = batch;
                _bayeux.parseTo(messageString, messages);
            }
            return messages.toArray(new Message[messages.size()]);
        }
        catch(IOException x)
        {
            throw x;
        }
        catch(Exception x)
        {
            return handleJSONParseException(request, messageString, x);
        }
    }

    /**
     * Override to customize the handling of JSON parse exceptions.
     * Default behavior is to log at warn level on logger "org.cometd.json" and to throw a ServletException that
     * wraps the original exception.
     *
     * @param request the request object
     * @param messageString the JSON text, if available; can be null if the JSON is not buffered before being parsed.
     * @param x the exception thrown during parsing
     * @return a non-null array of messages, possibly empty, if the JSON parse exception is recoverable
     * @throws ServletException if the JSON parsing is not recoverable
     */
    protected Message[] handleJSONParseException(HttpServletRequest request, String messageString, Exception x) throws ServletException
    {
        Log.getLogger("org.cometd.json").warn("Exception parsing JSON: " + messageString, x);
        throw new ServletException("Exception parsing JSON: |"+messageString+"|", x);
    }
}
