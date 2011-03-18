package org.cometd.javascript;

import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class XMLHttpRequestExchange extends ScriptableObject
{
    private CometdExchange exchange;

    public XMLHttpRequestExchange()
    {
    }

    public void jsConstructor(Object cookieStore, Object threadModel, Scriptable thiz, String method, String url, boolean async)
    {
        exchange = new CometdExchange((HttpCookieStore)cookieStore, (ThreadModel)threadModel, thiz, method, url, async);
    }

    public String getClassName()
    {
        return "XMLHttpRequestExchange";
    }

    public HttpExchange getHttpExchange()
    {
        return exchange;
    }

    public boolean isAsynchronous()
    {
        return exchange.isAsynchronous();
    }

    public void await() throws InterruptedException
    {
        exchange.waitForDone();
        exchange.notifyReadyStateChange();
    }

    public void jsFunction_addRequestHeader(String name, String value)
    {
        exchange.addRequestHeader(name, value);
    }

    public String jsGet_method()
    {
        return exchange.getMethod();
    }

    public void jsFunction_setRequestContent(String data) throws UnsupportedEncodingException
    {
        exchange.setRequestContent(data);
    }

    public int jsGet_readyState()
    {
        return exchange.getReadyState();
    }

    public String jsGet_responseText()
    {
        return exchange.getResponseText();
    }

    public int jsGet_responseStatus()
    {
        return exchange.getResponseStatus();
    }

    public String jsGet_responseStatusText()
    {
        return exchange.getResponseStatusText();
    }

    public void jsFunction_cancel()
    {
        exchange.cancel();
    }

    public String jsFunction_getAllResponseHeaders()
    {
        return exchange.getAllResponseHeaders();
    }

    public String jsFunction_getResponseHeader(String name)
    {
        return exchange.getResponseHeader(name);
    }

    public void send(HttpClient httpClient) throws Exception
    {
        exchange.send(httpClient);
    }

    public static class CometdExchange extends ContentExchange
    {
        public enum ReadyState
        {
            UNSENT, OPENED, HEADERS_RECEIVED, LOADING, DONE
        }

        private final Logger logger = Log.getLogger(getClass().getName());
        private final HttpCookieStore cookieStore;
        private final ThreadModel threads;
        private volatile Scriptable thiz;
        private final boolean async;
        private volatile boolean aborted;
        private volatile ReadyState readyState = ReadyState.UNSENT;
        private volatile String responseText;
        private volatile String responseStatusText;

        public CometdExchange(HttpCookieStore cookieStore, ThreadModel threads, Scriptable thiz, String method, String url, boolean async)
        {
            super(true);
            this.cookieStore = cookieStore;
            this.threads = threads;
            this.thiz = thiz;
            setMethod(method == null ? "GET" : method.toUpperCase());
            setURL(url);
            this.async = async;
            aborted = false;
            readyState = ReadyState.OPENED;
            responseStatusText = null;
            getRequestFields().clear();
            if (async)
                notifyReadyStateChange();
            logger.setDebugEnabled(true);
        }

        public boolean isAsynchronous()
        {
            return async;
        }

        private void notifyReadyStateChange()
        {
            Object onReadyStateChange = ScriptableObject.getProperty(thiz, "onreadystatechange");
            if (onReadyStateChange instanceof Function)
                threads.execute(thiz, thiz, (Function)onReadyStateChange);
        }

        public void send(HttpClient httpClient) throws Exception
        {
            String cookies = cookieStore.jsFunction_get(getScheme().toString("UTF-8"), getAddress().toString(), "");
            if (cookies.length() > 0)
                setRequestHeader(HttpHeaders.COOKIE, cookies);
            httpClient.send(this);
            logger.debug("Exchange {} submitted", this);
        }

        @Override
        public void cancel()
        {
            super.cancel();
            aborted = true;
            responseText = null;
            getRequestFields().clear();
            if (!async || readyState == ReadyState.HEADERS_RECEIVED || readyState == ReadyState.LOADING)
            {
                readyState = ReadyState.DONE;
                notifyReadyStateChange();
            }
            readyState = ReadyState.UNSENT;
        }

        public int getReadyState()
        {
            return readyState.ordinal();
        }

        public String getResponseText()
        {
            return responseText;
        }

        public String getResponseStatusText()
        {
            return responseStatusText;
        }

        public void setRequestContent(String content) throws UnsupportedEncodingException
        {
            setRequestContent(new ByteArrayBuffer(content, "UTF-8"));
        }

        public String getAllResponseHeaders()
        {
            return getResponseFields().toString();
        }

        public String getResponseHeader(String name)
        {
            return getResponseFields().getStringField(name);
        }

        @Override
        protected void onResponseStatus(Buffer version, int status, Buffer statusText) throws IOException
        {
            super.onResponseStatus(version, status, statusText);
            this.responseStatusText = new String(statusText.asArray(), "UTF-8");
        }

        @Override
        protected void onResponseHeader(Buffer name, Buffer value) throws IOException
        {
            super.onResponseHeader(name, value);
            int headerName = HttpHeaders.CACHE.getOrdinal(name);
            if (headerName == HttpHeaders.SET_COOKIE_ORDINAL)
            {
                try
                {
                    cookieStore.jsFunction_set(getScheme().toString("UTF-8"), getAddress().toString(), "", value.toString("UTF-8"));
                }
                catch (Exception x)
                {
                    throw (IOException)new IOException().initCause(x);
                }
            }
        }

        @Override
        protected void onResponseHeaderComplete() throws IOException
        {
            if (!aborted)
            {
                if (async)
                {
                    readyState = ReadyState.HEADERS_RECEIVED;
                    notifyReadyStateChange();
                }
            }
        }

        @Override
        protected void onResponseContent(Buffer buffer) throws IOException
        {
            super.onResponseContent(buffer);
            if (!aborted)
            {
                if (async)
                {
                    if (readyState != ReadyState.LOADING)
                    {
                        readyState = ReadyState.LOADING;
                        notifyReadyStateChange();
                    }
                }
            }
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            if (!aborted)
            {
                logger.debug("Exchange {} completed", this);
                responseText = getResponseContent();
                readyState = ReadyState.DONE;
                if (async)
                    notifyReadyStateChange();
            }
        }

        @Override
        protected void onException(Throwable x)
        {
            if (!(x instanceof EOFException))
                super.onException(x);
        }

        @Override
        protected void onRequestCommitted() throws IOException
        {
            super.onRequestCommitted();
            logger.debug("Exchange {} committed", this);
        }

        @Override
        protected void onRequestComplete() throws IOException
        {
            super.onRequestComplete();
            logger.debug("Exchange {} sent", this);
        }
    }
}
