package org.cometd.bayeux;

public interface Bayeux
{
    /** ServletContext attribute name used to obtain the Bayeux object */
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
    /**http helpers - application/json content type*/
    public static final String JSON_CONTENT_TYPE="application/json";
    /**http helpers - parameter name for json message*/
    public static final String MESSAGE_PARAMETER="message";
    /**http helpers - name of the jsonp parameter*/
    public static final String JSONP_PARAMETER="jsonp";
    /**http helpers - default name of the jsonp callback function*/
    public static final String JSONP_DEFAULT_NAME="jsonpcallback";
    
    void addExtension(Extension extension);
}
