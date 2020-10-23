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

import java.util.EventListener;
import java.util.List;
import java.util.Set;

/**
 * <p>The {@link Bayeux} interface is the common API for both client-side and
 * server-side configuration and usage of the Bayeux object.</p>
 * <p>The {@link Bayeux} object handles configuration options and a set of
 * transports that is negotiated with the server.</p>
 *
 * @see Transport
 */
public interface Bayeux {
    /**
     * @return the set of known transport names of this Bayeux object.
     * @see #getAllowedTransports()
     */
    Set<String> getKnownTransportNames();

    /**
     * @param transport the transport name
     * @return the transport with the given name or null
     * if no such transport exist
     */
    Transport getTransport(String transport);

    /**
     * @return the ordered list of transport names that will be used in the
     * negotiation of transports with the other peer.
     * @see #getKnownTransportNames()
     */
    List<String> getAllowedTransports();

    /**
     * @param qualifiedName the configuration option name
     * @return the configuration option with the given {@code qualifiedName}
     * @see #setOption(String, Object)
     * @see #getOptionNames()
     */
    Object getOption(String qualifiedName);

    /**
     * @param qualifiedName the configuration option name
     * @param value         the configuration option value
     * @see #getOption(String)
     */
    void setOption(String qualifiedName, Object value);

    /**
     * @return the set of configuration options
     * @see #getOption(String)
     */
    Set<String> getOptionNames();

    /**
     * <p>The common base interface for Bayeux listeners.</p>
     * <p>Specific sub-interfaces define what kind of events listeners will be notified.</p>
     */
    interface BayeuxListener extends EventListener {
    }

    /**
     * <p>Validates Bayeux protocol elements such as channel ids and message ids.</p>
     */
    class Validator {
        public static boolean isValidChannelId(String channelId) {
            if (channelId.length() < 2) {
                return false;
            }
            if (channelId.charAt(0) != '/') {
                return false;
            }
            for (int i = 1; i < channelId.length(); ++i) {
                char c = channelId.charAt(i);
                if (isAlpha(c) || isNumeric(c) || isAllowed(c)) {
                    continue;
                }
                return false;
            }
            return true;
        }

        public static boolean isValidMessageId(String messageId) {
            if (messageId.length() < 1) {
                return false;
            }
            for (int i = 0; i < messageId.length(); ++i) {
                char c = messageId.charAt(i);
                if (isAlpha(c) || isNumeric(c)) {
                    continue;
                }
                return false;
            }
            return true;
        }

        private static boolean isAlpha(char c) {
            if (c >= 'A' && c <= 'Z') {
                return true;
            }
            return c >= 'a' && c <= 'z';
        }

        private static boolean isNumeric(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isAllowed(char c) {
            switch (c) {
                case ' ':
                case '!':
                case '#':
                case '$':
                case '(':
                case ')':
                case '*':
                case '+':
                case '-':
                case '.':
                case '/':
                case '@':
                case '_':
                case '{':
                case '~':
                case '}':
                    return true;
                default:
                    return false;
            }
        }
    }
}
