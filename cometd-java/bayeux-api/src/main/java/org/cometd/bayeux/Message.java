package org.cometd.bayeux;

import java.util.Map;

/** Bayeux Message interface.
 * <p>
 * The API of a bayeux message, which consists mainly of convenience methods
 * to access the known fields of the message map.
 * <p>
 * This interface comes in both an Immutable and Mutable variation. Mutability 
 * may be deeply enforced by an implementation, so that it is not correct
 * to cast a passed Message, to a Message.Mutable, even if the implementation
 * allows this.
 *  
 */
public interface Message extends Map<String, Object>
{
    public static final String CLIENT_FIELD="clientId";
    public static final String DATA_FIELD="data";
    public static final String CHANNEL_FIELD="channel";
    public static final String ID_FIELD="id";
    public static final String ERROR_FIELD="error";
    public static final String TIMESTAMP_FIELD="timestamp";
    public static final String TRANSPORT_FIELD="transport";
    public static final String ADVICE_FIELD="advice";
    public static final String SUCCESSFUL_FIELD="successful";
    public static final String SUBSCRIPTION_FIELD="subscription";
    public static final String EXT_FIELD="ext";
    public static final String CONNECTION_TYPE_FIELD="connectionType";
    public static final String VERSION_FIELD="version";
    public static final String MIN_VERSION_FIELD="minimumVersion";
    public static final String SUPPORTED_CONNECTION_TYPES_FIELD ="supportedConnectionTypes";
    public static final String RECONNECT_FIELD = "reconnect";
    public static final String INTERVAL_FIELD = "interval";
    public static final String RETRY_RESPONSE = "retry";
    public static final String HANDSHAKE_RESPONSE = "handshake";
    public static final String NONE_RESPONSE = "none";
    public static final String SERVICE="/service";

    Map<String, Object> getAdvice();
    String getChannelId();
    String getClientId();
    Object getData();
    boolean isMeta();

    Map<String, Object> getDataAsMap();
    Map<String, Object> getExt();
    String getId();
    
    String getJSON();

    interface Mutable extends Message
    {
        Map<String, Object> getAdvice(boolean create);
        Map<String, Object> getDataAsMap(boolean create);
        Map<String, Object> getExt(boolean create);
        void setChannelId(String channelId);

        void setClientId(String clientId);
        void setData(Object data);
        void setId(String id);
    }
}
