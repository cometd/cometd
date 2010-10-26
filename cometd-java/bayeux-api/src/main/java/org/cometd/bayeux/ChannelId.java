// ========================================================================
// Copyright 2007 Mort Bay Consulting Pty. Ltd.
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

package org.cometd.bayeux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Holder of a channel ID broken into path segments
 */
public class ChannelId
{
    public final static String WILD="*";
    public final static String DEEPWILD="**";

    private final String  _name;
    private final String[] _segments;
    private final int _wild;
    private final List<String> _wilds;
    private final String _parent;

    public ChannelId(String name)
    {
        _name=name;
        if (name == null || name.length() == 0 || name.charAt(0) != '/' || "/".equals(name))
            throw new IllegalArgumentException(name);

        String[] wilds;

        if (name.charAt(name.length() - 1) == '/')
            name=name.substring(0,name.length() - 1);

        _segments=name.substring(1).split("/");
        wilds=new String[_segments.length+1];
        StringBuilder b=new StringBuilder();
        b.append('/');

        for (int i=0;i<_segments.length;i++)
        {
            if (_segments[i]==null || _segments[i].length()==0)
                throw new IllegalArgumentException(name);

            if (i>0)
                b.append(_segments[i-1]).append('/');
            wilds[_segments.length-i]=b+"**";
        }
        wilds[0]=b+"*";
        _parent=_segments.length==1?null:b.substring(0,b.length()-1);

        if (_segments.length == 0)
            _wild=0;
        else if (WILD.equals(_segments[_segments.length - 1]))
            _wild=1;
        else if (DEEPWILD.equals(_segments[_segments.length - 1]))
            _wild=2;
        else
            _wild=0;

        if (_wild==0)
            _wilds=Collections.unmodifiableList(Arrays.asList(wilds));
        else
            _wilds=Collections.emptyList();
    }

    public boolean isWild()
    {
        return _wild > 0;
    }

    public boolean isDeepWild()
    {
        return _wild > 1;
    }

    public boolean isMeta()
    {
        return _segments.length>0 && "meta".equals(_segments[0]);
    }

    public boolean isService()
    {
        return _segments.length>0 && "service".equals(_segments[0]);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;

        if (obj instanceof ChannelId)
        {
            ChannelId id = (ChannelId)obj;
            if (id.depth()==depth())
            {
                for (int i=id.depth();i-->0;)
                    if (!id.getSegment(i).equals(getSegment(i)))
                        return false;
                return true;
            }
        }

        return false;
    }

    /* ------------------------------------------------------------ */
    /** Match channel IDs with wildcard support
     * @param name
     * @return true if this channelID matches the passed channel ID. If this channel is wild, then matching is wild.
     * If the passed channel is wild, then it is the same as an equals call.
     */
    public boolean matches(ChannelId name)
    {
        if (name.isWild())
            return equals(name);

        switch(_wild)
        {
            case 0:
                return equals(name);
            case 1:
                if (name._segments.length != _segments.length)
                    return false;
                for (int i=_segments.length - 1; i-- > 0;)
                    if (!_segments[i].equals(name._segments[i]))
                        return false;
                return true;

            case 2:
                if (name._segments.length < _segments.length)
                    return false;
                for (int i=_segments.length - 1; i-- > 0;)
                    if (!_segments[i].equals(name._segments[i]))
                        return false;
                return true;
        }
        return false;
    }

    @Override
    public int hashCode()
    {
        return _name.hashCode();
    }

    @Override
    public String toString()
    {
        return _name;
    }

    public int depth()
    {
        return _segments.length;
    }

    /* ------------------------------------------------------------ */
    public boolean isAncestorOf(ChannelId id)
    {
        if (isWild() || depth() >= id.depth())
            return false;

        for (int i=_segments.length; i--> 0;)
        {
            if (!_segments[i].equals(id._segments[i]))
                return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    public boolean isParentOf(ChannelId id)
    {
        if (isWild() || depth() != id.depth()-1)
            return false;

        for (int i=_segments.length; i--> 0;)
        {
            if (!_segments[i].equals(id._segments[i]))
                return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    public String getParent()
    {
        return _parent;
    }

    /* ------------------------------------------------------------ */
    public String getSegment(int i)
    {
        if (i > _segments.length)
            return null;
        return _segments[i];
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The list of wilds channels that match this channel, or
     * the empty list if this channel is already wild.
     */
    public List<String> getWilds()
    {
        return _wilds;
    }

    public static boolean isMeta(String channelId)
    {
        return channelId!=null && channelId.startsWith("/meta/");
    }

    public static boolean isService(String channelId)
    {
        return channelId!=null && channelId.startsWith("/service/");
    }
}
