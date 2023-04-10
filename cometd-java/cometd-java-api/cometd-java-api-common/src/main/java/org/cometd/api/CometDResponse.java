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
package org.cometd.api;

public interface CometDResponse
{
    int SC_INTERNAL_SERVER_ERROR = 500;
    int SC_BAD_REQUEST = 400;

    void addHeader(String name, String value);

    String getCharacterEncoding();

    CometDOutput getOutput();

    void setContentType(String contentType);
}
