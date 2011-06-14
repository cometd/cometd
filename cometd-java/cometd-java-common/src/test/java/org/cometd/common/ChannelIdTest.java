/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.common;

import org.cometd.bayeux.ChannelId;
import org.junit.Assert;
import org.junit.Test;

public class ChannelIdTest
{
    @Test
    public void testDepth()
    {
        Assert.assertEquals(1, new ChannelId("/foo").depth());
        Assert.assertEquals(1,new ChannelId("/foo/").depth());
        Assert.assertEquals(2,new ChannelId("/foo/bar").depth());
        Assert.assertEquals(2,new ChannelId("/foo/bar/").depth());
        Assert.assertEquals(3,new ChannelId("/foo/bar/*").depth());
        Assert.assertEquals(3,new ChannelId("/foo/bar/**").depth());
    }

    @Test
    public void testSegments()
    {
        ChannelId channel = new ChannelId("/foo/bar");

        Assert.assertEquals("foo",channel.getSegment(0));
        Assert.assertEquals("bar",channel.getSegment(1));

        Assert.assertNull(channel.getSegment(2));
        Assert.assertNull(channel.getSegment(3));
    }

    @Test
    public void testIsXxx()
    {
        ChannelId id;

        id = new ChannelId("/foo/bar");
        Assert.assertFalse(id.isDeepWild());
        Assert.assertFalse(id.isMeta());
        Assert.assertFalse(id.isService());
        Assert.assertFalse(id.isWild());

        id = new ChannelId("/foo/*");
        Assert.assertTrue(id.isShallowWild());
        Assert.assertFalse(id.isDeepWild());
        Assert.assertFalse(id.isMeta());
        Assert.assertFalse(id.isService());
        Assert.assertTrue(id.isWild());

        id = new ChannelId("/foo/**");
        Assert.assertTrue(id.isDeepWild());
        Assert.assertFalse(id.isMeta());
        Assert.assertFalse(id.isService());
        Assert.assertTrue(id.isWild());

        id = new ChannelId("/meta/bar");
        Assert.assertFalse(id.isDeepWild());
        Assert.assertTrue(id.isMeta());
        Assert.assertFalse(id.isService());
        Assert.assertFalse(id.isWild());

        id = new ChannelId("/service/bar");
        Assert.assertFalse(id.isDeepWild());
        Assert.assertFalse(id.isMeta());
        Assert.assertTrue(id.isService());
        Assert.assertFalse(id.isWild());

        id = new ChannelId("/service/**");
        Assert.assertTrue(id.isDeepWild());
        Assert.assertFalse(id.isMeta());
        Assert.assertTrue(id.isService());
        Assert.assertTrue(id.isWild());
    }

    @Test
    public void testStaticIsXxx()
    {
        Assert.assertTrue(ChannelId.isMeta("/meta/bar"));
        Assert.assertFalse(ChannelId.isMeta("/foo/bar"));
        Assert.assertTrue(ChannelId.isService("/service/bar"));
        Assert.assertFalse(ChannelId.isService("/foo/bar"));
        Assert.assertFalse(ChannelId.isMeta("/"));
        Assert.assertFalse(ChannelId.isService("/"));
    }

    @Test
    public void testIsParent()
    {
        ChannelId foo = new ChannelId("/foo");
        ChannelId bar = new ChannelId("/bar");
        ChannelId foobar = new ChannelId("/foo/bar");
        ChannelId foobarbaz = new ChannelId("/foo/bar/baz");

        Assert.assertFalse(foo.isParentOf(foo));
        Assert.assertTrue(foo.isParentOf(foobar));
        Assert.assertFalse(foo.isParentOf(foobarbaz));

        Assert.assertFalse(foobar.isParentOf(foo));
        Assert.assertFalse(foobar.isParentOf(foobar));
        Assert.assertTrue(foobar.isParentOf(foobarbaz));

        Assert.assertFalse(bar.isParentOf(foo));
        Assert.assertFalse(bar.isParentOf(foobar));
        Assert.assertFalse(bar.isParentOf(foobarbaz));

        Assert.assertFalse(foo.isAncestorOf(foo));
        Assert.assertTrue(foo.isAncestorOf(foobar));
        Assert.assertTrue(foo.isAncestorOf(foobarbaz));

        Assert.assertFalse(foobar.isAncestorOf(foo));
        Assert.assertFalse(foobar.isAncestorOf(foobar));
        Assert.assertTrue(foobar.isAncestorOf(foobarbaz));

        Assert.assertFalse(bar.isAncestorOf(foo));
        Assert.assertFalse(bar.isAncestorOf(foobar));
        Assert.assertFalse(bar.isAncestorOf(foobarbaz));

    }

    @Test
    public void testEquals()
    {
        ChannelId foobar0 = new ChannelId("/foo/bar");
        ChannelId foobar1 = new ChannelId("/foo/bar/");
        ChannelId foo = new ChannelId("/foo");
        ChannelId wild = new ChannelId("/foo/*");
        ChannelId deep = new ChannelId("/foo/**");

        Assert.assertTrue(foobar0.equals(foobar0));
        Assert.assertTrue(foobar0.equals(foobar1));

        Assert.assertFalse(foobar0.equals(foo));
        Assert.assertFalse(foobar0.equals(wild));
        Assert.assertFalse(foobar0.equals(deep));
    }

    @Test
    public void testMatches()
    {
        ChannelId foobar0 = new ChannelId("/foo/bar");
        ChannelId foobar1 = new ChannelId("/foo/bar");
        ChannelId foobarbaz = new ChannelId("/foo/bar/baz");
        ChannelId foo = new ChannelId("/foo");
        ChannelId wild = new ChannelId("/foo/*");
        ChannelId deep = new ChannelId("/foo/**");

        Assert.assertTrue(foobar0.matches(foobar0));
        Assert.assertTrue(foobar0.matches(foobar1));

        Assert.assertFalse(foo.matches(foobar0));
        Assert.assertTrue(wild.matches(foobar0));
        Assert.assertTrue(deep.matches(foobar0));

        Assert.assertFalse(foo.matches(foobarbaz));
        Assert.assertFalse(wild.matches(foobarbaz));
        Assert.assertTrue(deep.matches(foobarbaz));

    }

    @Test
    public void testWilds()
    {
        ChannelId id;

        id=new ChannelId("/foo/bar/*");
        Assert.assertEquals(0,id.getWilds().size());

        id=new ChannelId("/foo");
        Assert.assertEquals(2,id.getWilds().size());
        Assert.assertEquals("/*",id.getWilds().get(0));
        Assert.assertEquals("/**",id.getWilds().get(1));

        id=new ChannelId("/foo/bar");
        Assert.assertEquals(3,id.getWilds().size());
        Assert.assertEquals("/foo/*",id.getWilds().get(0));
        Assert.assertEquals("/foo/**",id.getWilds().get(1));
        Assert.assertEquals("/**",id.getWilds().get(2));

        id=new ChannelId("/foo/bar/bob");
        Assert.assertEquals(4,id.getWilds().size());
        Assert.assertEquals("/foo/bar/*",id.getWilds().get(0));
        Assert.assertEquals("/foo/bar/**",id.getWilds().get(1));
        Assert.assertEquals("/foo/**",id.getWilds().get(2));
        Assert.assertEquals("/**",id.getWilds().get(3));

    }
}
