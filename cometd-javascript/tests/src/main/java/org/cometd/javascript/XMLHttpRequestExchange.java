/*
 * Copyright (c) 2008-2020 the original author or authors.
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
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This class is the underlying implementation of JavaScript's
 * {@code window.XMLHttpRequest} in {@code browser.js}.</p>
 */
public class XMLHttpRequestExchange {
    private final CometDExchange exchange;

    public XMLHttpRequestExchange(Object client, JavaScript javaScript, Object jsThis, String httpMethod, String url, boolean async) {
        exchange = new CometDExchange((XMLHttpRequestClient)client, javaScript, jsThis, httpMethod, url, async);
    }

    public void addRequestHeader(String name, String value) {
        exchange.getRequest().headers(headers -> headers.put(name, value));
    }

    public String getMethod() {
        return exchange.getRequest().getMethod();
    }

    public void setRequestContent(String data) {
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
        private final Object jsThis;
        private final boolean async;
        private volatile boolean aborted;
        private volatile ReadyState readyState;
        private volatile String requestText;
        private volatile String responseText;
        private volatile int responseStatus;
        private volatile String responseStatusText;

        public CometDExchange(XMLHttpRequestClient client, JavaScript javaScript, Object jsThis, String httpMethod, String url, boolean async) {
            super(client.getHttpClient().newRequest(url));
            getRequest().method(HttpMethod.fromString(httpMethod));
            this.javaScript = javaScript;
            this.jsThis = jsThis;
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
            notify(sync, "readystatechange");
        }

        private void notifyLoad() {
            notify(true, "load");
        }

        private void notifyError() {
            notify(true, "error");
        }

        private void notifyAbort() {
            notify(false, "abort");
        }

        private void notify(boolean sync, String event) {
            javaScript.invoke(sync, jsThis, "on" + event);
        }

        public void send() {
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
            getRequest().headers(HttpFields.Mutable::clear);
            if (readyState == ReadyState.HEADERS_RECEIVED || readyState == ReadyState.LOADING) {
                readyState = ReadyState.DONE;
                if (isAsynchronous()) {
                    notifyReadyStateChange(false);
                    notifyAbort();
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
            getRequest().body(new StringRequestContent(content));
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
                    notifyError();
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
