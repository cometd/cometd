package org.cometd.bayeux;

import java.util.Map;

/**
 * @version $Revision$ $Date: 2009-12-08 09:42:45 +1100 (Tue, 08 Dec 2009) $
 */
public interface Message extends Map<String, Object>
{
    /**Field names inside Bayeux messages - clientId field*/
    public static final String CLIENT_ID_FIELD ="clientId";
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
    public static final String RECONNECT_RETRY_VALUE = "retry";
    /**Field values inside Bayeux messages - handshake response*/
    public static final String RECONNECT_HANDSHAKE_VALUE = "handshake";
    /**Field values inside Bayeux messages - none response*/
    public static final String RECONNECT_NONE_VALUE = "none";
    /**Service channel names-starts with*/
    public static final String SERVICE="/service";

    String getId();
    String getClientId();
    String getChannelName();
    Object getData();
    Struct getExt(boolean create);
    Struct getAdvice();
    boolean isLazy();

    interface Mutable extends Message
    {
        Struct.Mutable getExt(boolean create);
        Struct.Mutable getAdvice();
        void setLazy(boolean lazy);
        Message getAssociated();
        void setAssociated(Message message);
    }
}
