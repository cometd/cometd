// ========================================================================
// Copyright 2007-2008 Dojo Foundation
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

package org.cometd;

import java.util.Collection;
import javax.servlet.http.HttpServletRequest;

/* ------------------------------------------------------------ */
/** Bayeux Server Interface.
 * <p>
 * This interface represents the server side API for the  Bayeux messaging protocol.
 * <p>
 * The implementation of Bayeux will be registered as a {@link javax.servlet.ServletContext} attribute
 * with the name "org.cometd.bayeux".  This may be set prior to the context being initialized
 * (if the instance is shared between contexts) or during context initialization.
 * <p>
 * Bayeux implementations must be thread safe and multiple threads may simultaneously
 * call Bayeux methods.
 *
 */
public interface Bayeux
{

    /** ServletContext attribute name used to obtain the Bayeux object */
    /* was DOJOX_COMETD_BAYEUX */
    public static final String ATTRIBUTE ="org.cometd.bayeux";

    /**Meta definitions for channels*/
    public static final String META="/meta";
    /**Meta definitions for channels*/
    public static final String META_SLASH="/meta/";
    /**Meta definitions for channels - connect message*/
    public static final String META_CONNECT="/meta/connect";
    /**Meta definitions for channels - client messsage*/
    public static final String META_CLIENT="/meta/client";
    /**Meta definitions for channels - disconnect messsage*/
    public static final String META_DISCONNECT="/meta/disconnect";
    /**Meta definitions for channels - handshake messsage*/
    public static final String META_HANDSHAKE="/meta/handshake";
    /**Meta definitions for channels - ping messsage*/
    public static final String META_PING="/meta/ping";
    /**Meta definitions for channels - status messsage*/
    public static final String META_STATUS="/meta/status";
    /**Meta definitions for channels - subscribe messsage*/
    public static final String META_SUBSCRIBE="/meta/subscribe";
    /**Meta definitions for channels - unsubscribe messsage*/
    public static final String META_UNSUBSCRIBE="/meta/unsubscribe";
    /*Field names inside Bayeux messages*/
    /**Field names inside Bayeux messages - clientId field*/
    public static final String CLIENT_FIELD="clientId";
    /**Field names inside Bayeux messages - data field*/
    public static final String DATA_FIELD="data";
    /**Field names inside Bayeux messages - channel field*/
    public static final String CHANNEL_FIELD="channel";
    /**Field names inside Bayeux messages - id field*/
    public static final String ID_FIELD="id";
    /**Field names inside Bayeux messages - error field*/
    public static final String ERROR_FIELD="error";
    /**Field names inside Bayeux messages - timestamp field*/
    public static final String TIMESTAMP_FIELD="timestamp";
    /**Field names inside Bayeux messages - transport field*/
    public static final String TRANSPORT_FIELD="transport";
    /**Field names inside Bayeux messages - advice field*/
    public static final String ADVICE_FIELD="advice";
    /**Field names inside Bayeux messages - successful field*/
    public static final String SUCCESSFUL_FIELD="successful";
    /**Field names inside Bayeux messages - subscription field*/
    public static final String SUBSCRIPTION_FIELD="subscription";
    /**Field names inside Bayeux messages - ext field*/
    public static final String EXT_FIELD="ext";
    /**Field names inside Bayeux messages - connectionType field*/
    public static final String CONNECTION_TYPE_FIELD="connectionType";
    /**Field names inside Bayeux messages - version field*/
    public static final String VERSION_FIELD="version";
    /**Field names inside Bayeux messages - minimumVersion field*/
    public static final String MIN_VERSION_FIELD="minimumVersion";
    /**Field names inside Bayeux messages - supportedConnectionTypes field*/
    public static final String SUPPORTED_CONNECTION_TYPES_FIELD ="supportedConnectionTypes";
    /**Field names inside Bayeux messages - json-comment-filtered field*/
    public static final String JSON_COMMENT_FILTERED_FIELD="json-comment-filtered";
    /**Field names inside Bayeux messages - reconnect field*/
    public static final String RECONNECT_FIELD = "reconnect";
    /**Field names inside Bayeux messages - interval field*/
    public static final String INTERVAL_FIELD = "interval";
    /**Field values inside Bayeux messages - retry response*/
    public static final String RETRY_RESPONSE = "retry";
    /**Field values inside Bayeux messages - handshake response*/
    public static final String HANDSHAKE_RESPONSE = "handshake";
    /**Field values inside Bayeux messages - none response*/
    public static final String NONE_RESPONSE = "none";
    /**Service channel names-starts with*/
    public static final String SERVICE="/service";
    /**Service channel names-trailing slash*/
    public static final String SERVICE_SLASH="/service/";
    /*Transport types*/
    /**Transport types - long polling*/
    public static final String TRANSPORT_LONG_POLL="long-polling";
    /**Transport types - callback polling*/
    public static final String TRANSPORT_CALLBACK_POLL="callback-polling";
    /**Transport types - iframe*/
    public static final String TRANSPORT_IFRAME="iframe";
    /**Transport types - flash*/
    public static final String TRANSPORT_FLASH="flash";

    /*http field names*/
    /**http helpers - text/json content type*/
    public static final String JSON_CONTENT_TYPE="text/json";
    /**http helpers - parameter name for json message*/
    public static final String MESSAGE_PARAMETER="message";
    /**http helpers - name of the jsonp parameter*/
    public static final String JSONP_PARAMETER="jsonp";
    /**http helpers - default name of the jsonp callback function*/
    public static final String JSONP_DEFAULT_NAME="jsonpcallback";

    /* ------------------------------------------------------------ */
    /** Get a Channel instance by ID.
     * @param channelId The Channel ID
     * @param create If true, a channel will be created if it does not exist.
     * @return A Channel instance or null if it does not exist and create is false.
     */
    public Channel getChannel(String channelId,boolean create);

    /* ------------------------------------------------------------ */
    /** Get all known channels.
     * @return A collection of all known channel instances.
     */
    public Collection<Channel> getChannels();

    /* ------------------------------------------------------------ */
    /** Get {@link Client} by ID.
     * @param clientId
     * @return A Client instance or null if the ID is not known
     */
    public Client getClient(String clientId);

    /* ------------------------------------------------------------ */
    /** Get a collection of all Clients.
     * The collection is copy of the underlying collection.
     * @return Collection of clients.
     */
    public Collection<Client> getClients();

    /* ------------------------------------------------------------ */
    /** Get the {@link SecurityPolicy} instance.
     * @return The current {@link SecurityPolicy} instance.
     */
    public SecurityPolicy getSecurityPolicy();

    /* ------------------------------------------------------------ */
    /** Check if channel exists.
     * @param channel
     * @return True if Bayeux has a channel with the channel name.
     */
    public boolean hasChannel(String channel);

    /* ------------------------------------------------------------ */
    public boolean hasClient(String clientId);

    /* ------------------------------------------------------------ */
    /** Create a new server side Client.
     * Server side clients can be used to interact with Bayeux with
     * publish and subscribe messaging.
     * @param idprefix An identifier to prefix to the client ID.
     * @return A {@link Client} instance with {@link Client#isLocal()} returning true.
     */
    public Client newClient(String idprefix);

    /* ------------------------------------------------------------ */
    public Channel removeChannel(String channel);

    /* ------------------------------------------------------------ */
    public Client removeClient(String clientId);

    /* ------------------------------------------------------------ */
    /** Set the security policy for the Bayeux instance.
     * <p>
     * The Security Policy will be called to check access for all handshakes,
     * subscriptions and publishing.
     *
     * @param securityPolicy The security policy instance.
     */
    public void setSecurityPolicy(SecurityPolicy securityPolicy);

    /* ------------------------------------------------------------ */
    /** Add a bayeux extension.
     * A bayeux extension may modify a message or return a new message.
     * @param ext An extension
     */
    public void addExtension(Extension ext);

    /* ------------------------------------------------------------ */
    public void addListener(BayeuxListener listener);

    /* ------------------------------------------------------------ */
    /**
     * @param size The size which if a client queue exceeds, forces a call to
     * {@link QueueListener#queueMaxed(Client, Message)} to check if the message should be
     * added.  If set to -1, there is no queue limit. If set to zero, messages are
     * not queued unless a {@link QueueListener} is applied that returns true.
     */
    public void setMaxClientQueue(int size);

    /* ------------------------------------------------------------ */
    public int getMaxClientQueue();

    /* ------------------------------------------------------------ */
    /** Get the current Servlet Request.
     * If the calling thread is in the context of a servlet call, then
     * return the request object.  This can be used to authenticate users and/or
     * perform other validation of the caller.
     * @return A servlet request or null if none in scope.
     */
    public HttpServletRequest getCurrentRequest();

}
