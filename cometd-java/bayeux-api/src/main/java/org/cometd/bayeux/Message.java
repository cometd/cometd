package org.cometd.bayeux;

import java.util.Map;

/**
 * <p>The Bayeux protocol exchange information by means of messages.</p>
 * <p>This interface represents the API of a Bayeux message, and consists
 * mainly of convenience methods to access the known fields of the message map.</p>
 * <p>This interface comes in both an immutable and {@link Mutable mutable} versions.<br/>
 * Mutability may be deeply enforced by an implementation, so that it is not correct
 * to cast a passed Message, to a Message.Mutable, even if the implementation
 * allows this.</p>
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public interface Message extends Map<String, Object>
{
    public static final String CLIENT_ID_FIELD = "clientId";
    public static final String DATA_FIELD = "data";
    public static final String CHANNEL_FIELD = "channel";
    public static final String ID_FIELD = "id";
    public static final String ERROR_FIELD = "error";
    public static final String TIMESTAMP_FIELD = "timestamp";
    public static final String TRANSPORT_FIELD = "transport";
    public static final String ADVICE_FIELD = "advice";
    public static final String SUCCESSFUL_FIELD = "successful";
    public static final String SUBSCRIPTION_FIELD = "subscription";
    public static final String EXT_FIELD = "ext";
    public static final String CONNECTION_TYPE_FIELD = "connectionType";
    public static final String VERSION_FIELD = "version";
    public static final String MIN_VERSION_FIELD = "minimumVersion";
    public static final String SUPPORTED_CONNECTION_TYPES_FIELD = "supportedConnectionTypes";
    public static final String RECONNECT_FIELD = "reconnect";
    public static final String INTERVAL_FIELD = "interval";
    public static final String RECONNECT_RETRY_VALUE = "retry";
    public static final String RECONNECT_HANDSHAKE_VALUE = "handshake";
    public static final String RECONNECT_NONE_VALUE = "none";

    /**
     * Convenience method to retrieve the {@link #ADVICE_FIELD}
     * @return the advice of the message
     */
    Map<String, Object> getAdvice();

    /**
     * Convenience method to retrieve the {@link #CHANNEL_FIELD}.
     * Bayeux message always have a non null channel.
     * @return the channel of the message
     */
    String getChannel();

    /**
     * Convenience method to retrieve the {@link #CLIENT_ID_FIELD}
     * @return the client id of the message
     */
    String getClientId();

    /**
     * Convenience method to retrieve the {@link #DATA_FIELD}
     * @return the data of the message
     * @see #getDataAsMap()
     */
    Object getData();

    /**
     * A messages that has a meta channel is dubbed a "meta message".
     * @return whether the channel's message is a meta channel
     */
    boolean isMeta();

    /**
     * Convenience method to retrieve the {@link #SUCCESSFUL_FIELD}
     * @return whether the message is successful
     */
    boolean isSuccessful();

    /**
     * @return the data of the message as a map
     * @see #getData()
     */
    Map<String, Object> getDataAsMap();

    /**
     * Convenience method to retrieve the {@link #EXT_FIELD}
     * @return the ext of the message
     */
    Map<String, Object> getExt();

    /**
     * Convenience method to retrieve the {@link #ID_FIELD}
     * @return the id of the message
     */
    String getId();

    /**
     * @return this message as a JSON string
     */
    String getJSON();

    /**
     * The mutable version of a {@link Message}
     */
    interface Mutable extends Message
    {
        /**
         * Convenience method to retrieve the {@link #ADVICE_FIELD} and create it if it does not exist
         * @param create whether to create the advice field if it does not exist
         * @return the advice of the message
         */
        Map<String, Object> getAdvice(boolean create);

        /**
         * Convenience method to retrieve the {@link #DATA_FIELD} and create it if it does not exist
         * @param create whether to create the data field if it does not exist
         * @return the data of the message
         */
        Map<String, Object> getDataAsMap(boolean create);

        /**
         * Convenience method to retrieve the {@link #EXT_FIELD} and create it if it does not exist
         * @param create whether to create the ext field if it does not exist
         * @return the ext of the message
         */
        Map<String, Object> getExt(boolean create);

        /**
         * @param channel the channel of this message
         */
        void setChannel(String channel);

        /**
         * @param clientId the client id of this message
         */
        void setClientId(String clientId);

        /**
         * @param data the data of this message
         */
        void setData(Object data);

        /**
         * @param id the id of this message
         */
        void setId(String id);

        /**
         * @param successful the successfulness of this message
         */
        void setSuccessful(boolean successful);
    }
}
