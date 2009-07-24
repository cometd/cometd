package org.cometd.server;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cometd.Bayeux;
import org.cometd.Message;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.ajax.JSON;

public class MessagePool
{
    final private ArrayQueue<MessageImpl> _messagePool;
    final private ArrayQueue<JSON.ReaderSource> _readerPool;

    /* ------------------------------------------------------------ */
    public MessagePool()
    {
        this(50);
    }

    /* ------------------------------------------------------------ */
    public MessagePool(int capacity)
    {
        _messagePool=new ArrayQueue<MessageImpl>(capacity,capacity);
        _readerPool=new ArrayQueue<JSON.ReaderSource>(capacity,capacity);
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
    public MessageImpl newMessage()
    {
        MessageImpl message=_messagePool.poll();
        if (message == null)
        {
            message=new MessageImpl(this);
        }
        message.incRef();
        return message;
    }

    /* ------------------------------------------------------------ */
    public MessageImpl newMessage(Message associated)
    {
        MessageImpl message=_messagePool.poll();
        if (message == null)
        {
            message=new MessageImpl(this);
        }
        message.incRef();
        if (associated != null)
            message.setAssociated(associated);
        return message;
    }

    /* ------------------------------------------------------------ */
    void recycleMessage(MessageImpl message)
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
        return "MessagePool:" + _messagePool.size() + "/" + _messagePool.getCapacity();

    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private StringMap _fieldStrings=new StringMap();
    private StringMap _valueStrings=new StringMap();
    {
        _fieldStrings.put(Bayeux.ADVICE_FIELD,Bayeux.ADVICE_FIELD);
        _fieldStrings.put(Bayeux.CHANNEL_FIELD,Bayeux.CHANNEL_FIELD);
        _fieldStrings.put(Bayeux.CLIENT_FIELD,Bayeux.CLIENT_FIELD);
        _fieldStrings.put(Bayeux.DATA_FIELD,Bayeux.DATA_FIELD);
        _fieldStrings.put(Bayeux.ERROR_FIELD,Bayeux.ERROR_FIELD);
        _fieldStrings.put(Bayeux.EXT_FIELD,Bayeux.EXT_FIELD);
        _fieldStrings.put(Bayeux.ID_FIELD,Bayeux.ID_FIELD);
        _fieldStrings.put(Bayeux.SUBSCRIPTION_FIELD,Bayeux.SUBSCRIPTION_FIELD);
        _fieldStrings.put(Bayeux.SUCCESSFUL_FIELD,Bayeux.SUCCESSFUL_FIELD);
        _fieldStrings.put(Bayeux.TIMESTAMP_FIELD,Bayeux.TIMESTAMP_FIELD);
        _fieldStrings.put(Bayeux.TRANSPORT_FIELD,Bayeux.TRANSPORT_FIELD);
        _fieldStrings.put("connectionType","connectionType");

        _valueStrings.put(Bayeux.META_CLIENT,Bayeux.META_CLIENT);
        _valueStrings.put(Bayeux.META_CONNECT,Bayeux.META_CONNECT);
        _valueStrings.put(Bayeux.META_DISCONNECT,Bayeux.META_DISCONNECT);
        _valueStrings.put(Bayeux.META_HANDSHAKE,Bayeux.META_HANDSHAKE);
        _valueStrings.put(Bayeux.META_SUBSCRIBE,Bayeux.META_SUBSCRIBE);
        _valueStrings.put(Bayeux.META_UNSUBSCRIBE,Bayeux.META_UNSUBSCRIBE);
        _valueStrings.put("long-polling","long-polling");
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private JSON _json=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new HashMap(8);
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
