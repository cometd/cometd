package org.cometd.bayeux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <p>Reification of a {@link Channel#getId() channel id} with methods to test properties
 * and compare with other {@link ChannelId}s.</p>
 * <p>A {@link ChannelId} breaks the channel id into path segments so that, for example,
 * {@code /foo/bar} breaks into {@code ["foo","bar"]}.</p>
 * <p>{@link ChannelId} can be wild, when they end with one or two wild characters {@code "*"};
 * a {@link ChannelId} is shallow wild if it ends with one wild character (for example {@code /foo/bar/*})
 * and deep wild if it ends with two wild characters (for example {@code /foo/bar/**}).</p>
 */
public class ChannelId
{
    public final static String WILD = "*";
    public final static String DEEPWILD = "**";

    private final String _id;
    private final String[] _segments;
    private final int _wild;
    private final List<String> _wilds;
    private final String _parent;

    /**
     * Constructs a new {@code ChannelId} with the given id
     *
     * @param id the channel id in string form
     */
    public ChannelId(String id)
    {
        if (id == null || id.length() == 0 || id.charAt(0) != '/' || "/".equals(id))
            throw new IllegalArgumentException("Invalid channel id: " + id);

        id = id.trim();
        if (id.charAt(id.length() - 1) == '/')
            id = id.substring(0, id.length() - 1);

        _id = id;

        _segments = id.substring(1).split("/");
        String[] wilds = new String[_segments.length + 1];
        StringBuilder b = new StringBuilder();
        b.append('/');

        for (int i = 0; i < _segments.length; ++i)
        {
            if (_segments[i] == null || _segments[i].length() == 0)
                throw new IllegalArgumentException("Invalid channel id: " + id);

            if (i > 0)
                b.append(_segments[i - 1]).append('/');

            wilds[_segments.length - i] = b + "**";
        }
        wilds[0] = b + "*";
        _parent = _segments.length == 1 ? null : b.substring(0, b.length() - 1);

        if (_segments.length == 0)
            _wild = 0;
        else if (WILD.equals(_segments[_segments.length - 1]))
            _wild = 1;
        else if (DEEPWILD.equals(_segments[_segments.length - 1]))
            _wild = 2;
        else
            _wild = 0;

        if (_wild == 0)
            _wilds = Collections.unmodifiableList(Arrays.asList(wilds));
        else
            _wilds = Collections.emptyList();
    }

    /**
     * @return whether this {@code ChannelId} is either {@link #isShallowWild() shallow wild}
     *         or {@link #isDeepWild() deep wild}
     */
    public boolean isWild()
    {
        return _wild > 0;
    }

    /**
     * <p>Shallow wild {@code ChannelId}s end with a single wild character {@code "*"}
     * and {@link #matches(ChannelId) match} non wild channels with
     * the same {@link #depth() depth}.</p>
     * <p>Example: {@code /foo/*} matches {@code /foo/bar}, but not {@code /foo/bar/baz}.</p>
     *
     * @return whether this {@code ChannelId} is a shallow wild channel id
     */
    public boolean isShallowWild()
    {
        return isWild() && !isDeepWild();
    }

    /**
     * <p>Deep wild {@code ChannelId}s end with a double wild character "**"
     * and {@link #matches(ChannelId) match} non wild channels with
     * the same or greater {@link #depth() depth}.</p>
     * <p>Example: {@code /foo/**} matches {@code /foo/bar} and {@code /foo/bar/baz}.</p>
     *
     * @return whether this {@code ChannelId} is a deep wild channel id
     */
    public boolean isDeepWild()
    {
        return _wild > 1;
    }

    /**
     * <p>A {@code ChannelId} is a meta {@code ChannelId} if it starts with {@code "/meta/"}.</p>
     *
     * @return whether the first segment is "meta"
     */
    public boolean isMeta()
    {
        return _segments.length > 0 && "meta".equals(_segments[0]);
    }

    /**
     * <p>A {@code ChannelId} is a service {@code ChannelId} if it starts with {@code "/service/"}.</p>
     *
     * @return whether the first segment is "service"
     */
    public boolean isService()
    {
        return _segments.length > 0 && "service".equals(_segments[0]);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;

        if (!(obj instanceof ChannelId))
            return false;

        ChannelId that = (ChannelId)obj;
        return _id.equals(that._id);
    }

    @Override
    public int hashCode()
    {
        return _id.hashCode();
    }

    /**
     * <p>Tests whether this {@code ChannelId} matches the given {@code ChannelId}.</p>
     * <p>If the given {@code ChannelId} is {@link #isWild() wild},
     * then it matches only if it is equal to this {@code ChannelId}.</p>
     * <p>If this {@code ChannelId} is non-wild,
     * then it matches only if it is equal to the given {@code ChannelId}.</p>
     * <p>Otherwise, this {@code ChannelId} is either shallow or deep wild, and
     * matches {@code ChannelId}s with the same number of segments (if it is
     * shallow wild), or {@code ChannelId}s with the same or a greater number of
     * segments (if it is deep wild).</p>
     *
     * @param channelId the channelId to match
     * @return true if this {@code ChannelId} matches the given {@code ChannelId}
     */
    public boolean matches(ChannelId channelId)
    {
        if (channelId.isWild())
            return equals(channelId);

        switch (_wild)
        {
            case 0:
            {
                return equals(channelId);
            }
            case 1:
            {
                if (channelId._segments.length != _segments.length)
                    return false;
                for (int i = _segments.length - 1; i-- > 0; )
                    if (!_segments[i].equals(channelId._segments[i]))
                        return false;
                return true;
            }
            case 2:
            {
                if (channelId._segments.length < _segments.length)
                    return false;
                for (int i = _segments.length - 1; i-- > 0; )
                    if (!_segments[i].equals(channelId._segments[i]))
                        return false;
                return true;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }

    @Override
    public String toString()
    {
        return _id;
    }

    /**
     * @return how many segments this {@code ChannelId} is made of
     * @see #getSegment(int)
     */
    public int depth()
    {
        return _segments.length;
    }

    /**
     * @param id the channel to test
     * @return whether this {@code ChannelId} is an ancestor of the given {@code ChannelId}
     * @see #isParentOf(ChannelId)
     */
    public boolean isAncestorOf(ChannelId id)
    {
        if (isWild() || depth() >= id.depth())
            return false;

        for (int i = _segments.length; i-- > 0; )
        {
            if (!_segments[i].equals(id._segments[i]))
                return false;
        }
        return true;
    }

    /**
     * @param id the channel to test
     * @return whether this {@code ChannelId} is the parent of the given {@code ChannelId}
     * @see #isAncestorOf(ChannelId)
     */
    public boolean isParentOf(ChannelId id)
    {
        if (isWild() || depth() != id.depth() - 1)
            return false;

        for (int i = _segments.length; i-- > 0; )
        {
            if (!_segments[i].equals(id._segments[i]))
                return false;
        }
        return true;
    }

    /**
     * @return the {@code ChannelId} parent of this {@code ChannelId}
     * @see #isParentOf(ChannelId)
     */
    public String getParent()
    {
        return _parent;
    }

    /**
     * @param i the segment index
     * @return the i-nth segment of this channel, or null if no such segment exist
     * @see #depth()
     */
    public String getSegment(int i)
    {
        if (i >= _segments.length)
            return null;
        return _segments[i];
    }

    /**
     * @return The list of wilds channels that match this channel, or
     *         the empty list if this channel is already wild.
     */
    public List<String> getWilds()
    {
        return _wilds;
    }

    /**
     * <p>Helper method to test if the string form of a {@code ChannelId}
     * represents a {@link #isMeta() meta} {@code ChannelId}.</p>
     *
     * @param channelId the channel id to test
     * @return whether the given channel id is a meta channel id
     */
    public static boolean isMeta(String channelId)
    {
        return channelId != null && channelId.startsWith("/meta/");
    }

    /**
     * <p>Helper method to test if the string form of a {@code ChannelId}
     * represents a {@link #isService()} service} {@code ChannelId}.</p>
     *
     * @param channelId the channel id to test
     * @return whether the given channel id is a service channel id
     */
    public static boolean isService(String channelId)
    {
        return channelId != null && channelId.startsWith("/service/");
    }
}
