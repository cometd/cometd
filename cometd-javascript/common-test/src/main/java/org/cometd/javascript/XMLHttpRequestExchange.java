/*
 * Copyright (c) 2008-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.javascript;

import java.io.EOFException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XMLHttpRequestExchange {
    private final CometDExchange exchange;

    public XMLHttpRequestExchange(Object client, JavaScript javaScript, ScriptObjectMirror thiz, String method, String url, boolean async) {
        exchange = new CometDExchange((XMLHttpRequestClient)client, javaScript, thiz, method, url, async);
    }

    public void addRequestHeader(String name, String value) {
        exchange.getRequest().header(name, value);
    }

    public String getMethod() {
        return exchange.getRequest().getMethod();
    }

    public void setRequestContent(String data) throws UnsupportedEncodingException {
        exchange.setRequestContent(data);
    }

    public int getReadyState() {
        return exchange.getReadyState();
    }

    public String getResponseText() {
        return exchange.getResponseText();
    }

    public int getResponseStatus() {
        return exchange.getResponseStatus();
    }

    public String getResponseStatusText() {
        return exchange.getResponseStatusText();
    }

    public void abort() {
        exchange.abort();
    }

    public String getAllResponseHeaders() {
        return exchange.getAllResponseHeaders();
    }

    public String getResponseHeader(String name) {
        return exchange.getResponseHeader(name);
    }

    public void send() throws Exception {
        exchange.send();
        try {
            if (!exchange.isAsynchronous()) {
                exchange.get(60, TimeUnit.SECONDS);
            }
        } catch (ExecutionException x) {
            Throwable cause = x.getCause();
            if (cause instanceof Exception) {
                throw (Exception)cause;
            } else {
                throw (Error)cause;
            }
        }
    }

    public static class CometDExchange extends FutureResponseListener {
        public enum ReadyState {
            UNSENT, OPENED, HEADERS_RECEIVED, LOADING, DONE
        }

        private final Logger logger = LoggerFactory.getLogger(getClass().getName());
        private final JavaScript javaScript;
        private final ScriptObjectMirror thiz;
        private final boolean async;
        private volatile boolean aborted;
        private volatile ReadyState readyState = ReadyState.UNSENT;
        private volatile String requestText;
        private volatile String responseText;
        private volatile int responseStatus;
        private volatile String responseStatusText;

        public CometDExchange(XMLHttpRequestClient client, JavaScript javaScript, ScriptObjectMirror thiz, String method, String url, boolean async) {
            super(client.getHttpClient().newRequest(url));
            getRequest().method(HttpMethod.fromString(method));
            this.javaScript = javaScript;
            this.thiz = thiz;
            this.async = async;
            this.aborted = false;
            this.readyState = ReadyState.OPENED;
            this.responseStatusText = null;
            if (async) {
                notifyReadyStateChange(false);
            }
        }

        public boolean isAsynchronous() {
            return async;
        }

        /**
         * If this method is invoked in the same stack of a JavaScript call,
         * then it must be asynchronous.
         * The reason is that the JavaScript may modify the onreadystatechange
         * function afterwards, and we would be notifying the wrong function.
         *
         * @param sync whether the call should be synchronous
         */
        private void notifyReadyStateChange(boolean sync) {
            if (logger.isDebugEnabled()) {
                logger.debug("Notifying onreadystatechange ({}) {}", readyState, getRequest().getURI());
            }
            javaScript.invoke(sync, thiz, "onreadystatechange");
        }

        private void notifyLoad() {
            javaScript.invoke(true, thiz, "onload");
        }

        private void notifyError(boolean sync) {
            javaScript.invoke(sync, thiz, "onerror");
        }

        public void send() throws Exception {
            if (logger.isDebugEnabled()) {
                logger.debug("Submitted {}", this);
            }
            getRequest().send(this);
        }

        public void abort() {
            cancel(false);
            if (logger.isDebugEnabled()) {
                logger.debug("Aborted {}", this);
            }
            aborted = true;
            responseText = null;
            getRequest().getHeaders().clear();
            if (readyState == ReadyState.HEADERS_RECEIVED || readyState == ReadyState.LOADING) {
                readyState = ReadyState.DONE;
                if (isAsynchronous()) {
                    notifyReadyStateChange(false);
                    notifyError(false);
                }
            } else {
                readyState = ReadyState.UNSENT;
            }
        }

        public int getReadyState() {
            return readyState.ordinal();
        }

        public String getResponseText() {
            return responseText;
        }

        public int getResponseStatus() {
            return responseStatus;
        }

        public String getResponseStatusText() {
            return responseStatusText;
        }

        public void setRequestContent(String content) {
            requestText = content;
            getRequest().content(new StringContentProvider(content));
        }

        public String getAllResponseHeaders() {
            return getRequest().getHeaders().toString();
        }

        public String getResponseHeader(String name) {
            return getRequest().getHeaders().get(name);
        }

        @Override
        public void onBegin(Response response) {
            super.onBegin(response);
            this.responseStatus = response.getStatus();
            this.responseStatusText = response.getReason();
        }

        @Override
        public void onHeaders(Response response) {
            super.onHeaders(response);
            if (!aborted) {
                readyState = ReadyState.HEADERS_RECEIVED;
                if (isAsynchronous()) {
                    notifyReadyStateChange(true);
                }
            }
        }

        @Override
        public void onContent(Response response, ByteBuffer content) {
            super.onContent(response, content);
            if (!aborted) {
                if (readyState != ReadyState.LOADING) {
                    readyState = ReadyState.LOADING;
                    if (isAsynchronous()) {
                        notifyReadyStateChange(true);
                    }
                }
            }
        }

        @Override
        public void onComplete(Result result) {
            if (result.isSucceeded()) {
                Response response = result.getResponse();
                if (logger.isDebugEnabled()) {
                    logger.debug("Succeeded ({}) {}", response.getStatus(), this);
                }
                if (!aborted) {
                    responseText = getContentAsString();
                    readyState = ReadyState.DONE;
                    if (isAsynchronous()) {
                        notifyReadyStateChange(true);
                        notifyLoad();
                    }
                }
            } else {
                Throwable failure = result.getFailure();
                if (!(failure instanceof EOFException)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Failed " + this, failure);
                    }
                }
                readyState = ReadyState.DONE;
                if (isAsynchronous()) {
                    notifyReadyStateChange(true);
                    notifyError(true);
                }
            }
            super.onComplete(result);
        }

        @Override
        public String toString() {
            if (requestText == null) {
                return String.format("%s(%s)", getRequest(), readyState);
            } else {
                return String.format("%s(%s)%n%s", getRequest(), readyState, requestText);
            }
        }
    }
}
