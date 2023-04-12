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
package org.cometd.server.servlet;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.cometd.api.CometDOutput;
import org.cometd.api.CometDResponse;

class ServletCometDResponse implements CometDResponse {
    private final HttpServletResponse response;
    private ServletCometDOutput output;

    public ServletCometDResponse(HttpServletResponse response) {
        this.response = response;
    }

    @Override
    public void addHeader(String name, String value) {
        response.addHeader(name, value);
    }

    @Override
    public String getCharacterEncoding() {
        return response.getCharacterEncoding();
    }

    @Override
    public CometDOutput getOutput() {
        if (output == null) {
            try {
                output = new ServletCometDOutput(response);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return output;
    }

    @Override
    public void setContentType(String contentType) {
        response.setContentType(contentType);
    }
}
