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
module org.cometd.server {
    exports org.cometd.server;
    exports org.cometd.server.authorizer;
    exports org.cometd.server.ext;
    exports org.cometd.server.filter;
    exports org.cometd.server.http;
    exports org.cometd.server.jmx;

    requires transitive jakarta.servlet;
    requires transitive org.cometd.api.server;
    requires transitive org.cometd.common;
    requires org.slf4j;

    // Only required when using JMX.
    requires static org.eclipse.jetty.jmx;
    // Only required when using Jetty's JSON.
    requires static org.eclipse.jetty.util.ajax;
}
