/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.server.handler;

import org.cometd.server.spi.CometDOutput;
import org.cometd.server.spi.CometDResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Response;

class HandlerCometDResponse implements CometDResponse {
    private final Response response;
    private CometDOutput cometDOutput;

    HandlerCometDResponse(Response response) {
        this.response = response;
    }

    @Override
    public void addHeader(String name, String value) {
        response.getHeaders().add(name, value);
    }

    @Override
    public CometDOutput getOutput() {
        if (cometDOutput == null) {
            cometDOutput = new HandlerCometDOutput(response);
        }
        return cometDOutput;
    }

    @Override
    public void setContentType(String contentType)
    {
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
    }
}
