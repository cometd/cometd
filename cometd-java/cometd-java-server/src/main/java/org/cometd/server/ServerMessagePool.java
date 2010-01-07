package org.cometd.server;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.util.ImmutableHashMap;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerMessagePool
{
    final private ConcurrentLinkedQueue<ServerMessageImpl> _messagePool;
    final private ConcurrentLinkedQueue<JSON.ReaderSource> _readerPool;

    /* ------------------------------------------------------------ */
    public ServerMessagePool()
    {
        this(50);
    }

    /* ------------------------------------------------------------ */
    public ServerMessagePool(int capacity)
    {
        _messagePool=new ConcurrentLinkedQueue<ServerMessageImpl>();
        _readerPool=new ConcurrentLinkedQueue<JSON.ReaderSource>();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the {@link JSON} instance used to convert data and ext fields
     */
    public JSON getJSON()
    {
        return _json;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param json
     *            the {@link JSON} instance used to convert data and ext fields
     */
    public void setJSON(JSON json)
    {
        _json=json;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the {@link JSON} instance used to serialize and deserialize
     *         bayeux bayeux messages
     */
    public JSON getMsgJSON()
    {
        return _msgJSON;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param msgJSON
     *            the {@link JSON} instance used to serialize and deserialize
     *            bayeux messages
     */
    public void setMsgJSON(JSON msgJSON)
    {
        _msgJSON=msgJSON;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the {@link JSON} instance used to deserialize batches of bayeux
     *         messages
     */
    public JSON getBatchJSON()
    {
        return _batchJSON;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param batchJSON
     *            the {@link JSON} instance used to convert batches of bayeux
     *            messages
     */
    public void setBatchJSON(JSON batchJSON)
    {
        _batchJSON=batchJSON;
    }

    /* ------------------------------------------------------------ */
    public ServerMessage.Mutable getServerMessage()
    {
        ServerMessageImpl message=_messagePool.poll();
        if (message == null)
            message=new ServerMessageImpl(this);
        return message.asMutable();
    }
    
    /* ------------------------------------------------------------ */
    void recycleMessage(ServerMessage message)
    {
        if (message instanceof ServerMessageImpl)
        {
            message.clear();
            _messagePool.offer((ServerMessageImpl)message);
        }
        else if (message instanceof ServerMessageImpl.MutableMessage)
        {
            message.clear();
            _messagePool.offer((ServerMessageImpl)((ServerMessageImpl.Mutable)message).asImmutable());
        }
    }

    /* ------------------------------------------------------------ */
    public Message[] parse(Reader reader) throws IOException
    {
        JSON.ReaderSource source=_readerPool.poll();
        if (source == null)
            source=new JSON.ReaderSource(reader);
        else
            source.setReader(reader);

        Object batch=_batchJSON.parse(source);
        _readerPool.offer(source);

        if (batch == null)
            return new Message[0];
        if (batch.getClass().isArray())
            return (Message[])batch;
        return new Message[]
        {(Message)batch};
    }

    /* ------------------------------------------------------------ */
    public Message[] parse(String s) throws IOException
    {
        Object batch=_batchJSON.parse(new JSON.StringSource(s));
        if (batch == null)
            return new Message[0];
        if (batch.getClass().isArray())
            return (Message[])batch;
        return new Message[]
        {(Message)batch};
    }

    /* ------------------------------------------------------------ */
    public void parseTo(String fodder, List<Message> messages)
    {
        Object batch=_batchJSON.parse(new JSON.StringSource(fodder));
        if (batch == null)
            return;
        if (batch.getClass().isArray())
        {
            Message[] msgs=(Message[])batch;
            for (int m=0; m < msgs.length; m++)
                messages.add(msgs[m]);
        }
        else
            messages.add((Message)batch);
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return "MessagePool:" + _messagePool.size();

    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private StringMap _fieldStrings=new StringMap();
    private StringMap _valueStrings=new StringMap();
    {
        _fieldStrings.put(Message.ADVICE_FIELD,Message.ADVICE_FIELD);
        _fieldStrings.put(Message.CHANNEL_FIELD,Message.CHANNEL_FIELD);
        _fieldStrings.put(Message.CLIENT_FIELD,Message.CLIENT_FIELD);
        _fieldStrings.put(Message.DATA_FIELD,Message.DATA_FIELD);
        _fieldStrings.put(Message.ERROR_FIELD,Message.ERROR_FIELD);
        _fieldStrings.put(Message.EXT_FIELD,Message.EXT_FIELD);
        _fieldStrings.put(Message.ID_FIELD,Message.ID_FIELD);
        _fieldStrings.put(Message.SUBSCRIPTION_FIELD,Message.SUBSCRIPTION_FIELD);
        _fieldStrings.put(Message.SUCCESSFUL_FIELD,Message.SUCCESSFUL_FIELD);
        _fieldStrings.put(Message.TIMESTAMP_FIELD,Message.TIMESTAMP_FIELD);
        _fieldStrings.put(Message.TRANSPORT_FIELD,Message.TRANSPORT_FIELD);
        _fieldStrings.put("connectionType","connectionType");

        _valueStrings.put(Channel.META_CONNECT,Channel.META_CONNECT);
        _valueStrings.put(Channel.META_DISCONNECT,Channel.META_DISCONNECT);
        _valueStrings.put(Channel.META_HANDSHAKE,Channel.META_HANDSHAKE);
        _valueStrings.put(Channel.META_SUBSCRIBE,Channel.META_SUBSCRIBE);
        _valueStrings.put(Channel.META_UNSUBSCRIBE,Channel.META_UNSUBSCRIBE);
        _valueStrings.put("long-polling","long-polling");
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private JSON _json=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new ImmutableHashMap<String, Object>().asMutable();
        }

        @Override
        protected String toString(char[] buffer, int offset, int length)
        {
            Map.Entry entry=_valueStrings.getEntry(buffer,offset,length);
            if (entry != null)
                return (String)entry.getValue();
            String s=new String(buffer,offset,length);
            return s;
        }
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private JSON _msgJSON=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return getServerMessage();
        }

        @Override
        protected String toString(char[] buffer, int offset, int length)
        {
            Map.Entry entry=_fieldStrings.getEntry(buffer,offset,length);
            if (entry != null)
                return (String)entry.getValue();

            String s=new String(buffer,offset,length);
            return s;
        }

        @Override
        protected JSON contextFor(String field)
        {
            return _json;
        }
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private JSON _batchJSON=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return getServerMessage();
        }

        @Override
        protected Object[] newArray(int size)
        {
            return new Message[size]; // todo recycle
        }

        @Override
        protected JSON contextFor(String field)
        {
            return _json;
        }

        @Override
        protected JSON contextForArray()
        {
            return _msgJSON;
        }
    };

}
