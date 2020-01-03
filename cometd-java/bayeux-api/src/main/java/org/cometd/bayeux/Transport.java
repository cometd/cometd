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
package org.cometd.bayeux;

import java.util.Set;

/**
 * <p>A transport abstract the details of the protocol used to send
 * Bayeux messages over the network, for example using HTTP or using
 * WebSocket.</p>
 * <p>{@link Transport}s have well known names and both a Bayeux client
 * and a Bayeux server can negotiate the transport they want to use by
 * exchanging the list of supported transport names.</p>
 * <p>Transports can be configured using <em>options</em>. The transport
 * implementation provides a set of {@link #getOptionNames() option names} that
 * it uses to configure itself and an {@link #getOptionPrefix() option prefix}
 * that allows specific tuning of the configuration.
 * Option prefixes may be composed of segments separated by the "." character.</p>
 * <p>For example, imagine to configure the transports for normal long polling,
 * for JSONP long polling and for WebSocket. All provide a common option name
 * called "timeout" and the JSONP long polling transport provides also a specific
 * option name called "callback".
 * The normal long polling transport has prefix "long-polling.json",
 * the JSONP long polling transport has prefix "long-polling.jsonp" and the
 * WebSocket long polling transport has prefix "ws". The first two prefixes
 * have 2 segments.</p>
 * <p>The configurator will asks the transports the set of option names, obtaining
 * ["timeout", "callback"]; then will ask each transport its prefix, obtaining
 * ["long-polling.json", "long-polling.jsonp"].
 * The configurator can now look in the configuration (for example a properties
 * file or servlet init parameters) for entries that combine the option names and
 * option prefix segments, such as:</p>
 * <ul>
 * <li>"timeout": specifies the timeout for all transports</li>
 * <li>"long-polling.timeout": specifies the timeout for both normal long polling
 * transport and JSONP long polling transport, but not for the WebSocket transport</li>
 * <li>"long-polling.jsonp.timeout": specifies the timeout for JSONP long polling
 * transport overriding more generic entries</li>
 * <li>"ws.timeout": specifies the timeout for WebSocket transport overriding more
 * generic entries</li>
 * <li>"long-polling.jsonp.callback": specifies the "callback" parameter for the
 * JSONP long polling transport.</li>
 * </ul>
 */
public interface Transport {
    /**
     * @return The well known name of this transport, used in transport negotiations
     * @see Bayeux#getAllowedTransports()
     */
    String getName();

    /**
     * @param name the configuration option name
     * @return the configuration option with the given {@code qualifiedName}
     * @see #getOptionNames()
     */
    Object getOption(String name);

    /**
     * @return the set of configuration options
     * @see #getOption(String)
     */
    Set<String> getOptionNames();

    /**
     * Specifies an option prefix made of string segments separated by the "."
     * character, used to override more generic configuration entries.
     *
     * @return the option prefix for this transport.
     */
    String getOptionPrefix();
}
