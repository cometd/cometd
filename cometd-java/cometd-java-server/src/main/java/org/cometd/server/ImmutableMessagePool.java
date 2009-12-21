package org.cometd.server;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.ajax.JSON;

public class ImmutableMessagePool
{
    final private ConcurrentLinkedQueue<ImmutableMessage> _messagePool;
    final private ConcurrentLinkedQueue<JSON.ReaderSource> _readerPool;

    /* ------------------------------------------------------------ */
    public ImmutableMessagePool()
    {
        this(50);
    }

    /* ------------------------------------------------------------ */
    public ImmutableMessagePool(int capacity)
    {
        _messagePool=new ConcurrentLinkedQueue<ImmutableMessage>();
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
    public Message.Mutable newMessage()
    {
        ImmutableMessage message=_messagePool.poll();
        if (message == null)
        {
            message=new ImmutableMessage(this);
        }
        message.incRef();
        return message.asMutable();
    }

    /* ------------------------------------------------------------ */
    public ImmutableMessage newMessage(Message associated)
    {
        ImmutableMessage message=_messagePool.poll();
        if (message == null)
        {
            message=new ImmutableMessage(this);
        }
        message.incRef();
        if (associated != null)
            message.setAssociated(associated);
        return message;
    }

    /* ------------------------------------------------------------ */
    void recycleMessage(ImmutableMessage message)
    {
        message.clear();
        _messagePool.offer(message);
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
        _fieldStrings.put(Message.CLIENT_ID_FIELD,Message.CLIENT_ID_FIELD);
        _fieldStrings.put(Message.DATA_FIELD,Message.DATA_FIELD);
        _fieldStrings.put(Message.ERROR_FIELD,Message.ERROR_FIELD);
        _fieldStrings.put(Message.EXT_FIELD,Message.EXT_FIELD);
        _fieldStrings.put(Message.ID_FIELD,Message.ID_FIELD);
        _fieldStrings.put(Message.SUBSCRIPTION_FIELD,Message.SUBSCRIPTION_FIELD);
        _fieldStrings.put(Message.SUCCESSFUL_FIELD,Message.SUCCESSFUL_FIELD);
        _fieldStrings.put(Message.TIMESTAMP_FIELD,Message.TIMESTAMP_FIELD);
        _fieldStrings.put(Message.TRANSPORT_FIELD,Message.TRANSPORT_FIELD);
        _fieldStrings.put("connectionType","connectionType");

        _valueStrings.put(Channel.MetaType.CONNECT.getChannelId(),Channel.MetaType.CONNECT.getChannelId());
        _valueStrings.put(Channel.MetaType.DISCONNECT.getChannelId(),Channel.MetaType.DISCONNECT.getChannelId());
        _valueStrings.put(Channel.MetaType.HANDSHAKE.getChannelId(),Channel.MetaType.HANDSHAKE.getChannelId());
        _valueStrings.put(Channel.MetaType.SUBSCRIBE.getChannelId(),Channel.MetaType.SUBSCRIBE.getChannelId());
        _valueStrings.put(Channel.MetaType.UNSUBSCRIBE.getChannelId(),Channel.MetaType.UNSUBSCRIBE.getChannelId());
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
            return newMessage();
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
            return newMessage();
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
