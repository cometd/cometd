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

package org.cometd.common;


/* ------------------------------------------------------------ */
/** Holder of a channel ID broken into path segments
 */
public class ChannelId
{
    public final static String WILD="*";
    public final static String DEEPWILD="**";

    private static final String[] ROOT={};
    private final String  _name;
    private final String[] _segments;
    private final int _wild;

    public ChannelId(String name)
    {
        _name=name;
        if (name == null || name.length() == 0 || name.charAt(0) != '/')
            throw new IllegalArgumentException(name);

        if ("/".equals(name))
        {
            _segments=ROOT;
        }
        else
        {
            if (name.charAt(name.length() - 1) == '/')
                name=name.substring(0,name.length() - 1);

            _segments=name.substring(1).split("/");
        }

        if (_segments.length == 0)
            _wild=0;
        else if (WILD.equals(_segments[_segments.length - 1]))
            _wild=1;
        else if (DEEPWILD.equals(_segments[_segments.length - 1]))
            _wild=2;
        else
            _wild=0;
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
     * @return
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

    public boolean isParentOf(ChannelId id)
    {
        if (isWild() || depth() >= id.depth())
            return false;

        for (int i = _segments.length - 1; i >= 0; --i)
		{
            if (!_segments[i].equals(id._segments[i]))
                return false;
		}
        return true;
    }

    public String getSegment(int i)
    {
        if (i > _segments.length)
            return null;
        return _segments[i];
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
