/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import java.util.List;

import org.cometd.bayeux.ChannelId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ChannelIdTest {
    @Test
    public void testDepth() {
        Assertions.assertEquals(1, new ChannelId("/foo").depth());
        Assertions.assertEquals(1, new ChannelId("/foo/").depth());
        Assertions.assertEquals(2, new ChannelId("/foo/bar").depth());
        Assertions.assertEquals(2, new ChannelId("/foo/bar/").depth());
        Assertions.assertEquals(3, new ChannelId("/foo/bar/*").depth());
        Assertions.assertEquals(3, new ChannelId("/foo/bar/**").depth());
    }

    @Test
    public void testSegments() {
        ChannelId channel = new ChannelId("/foo/bar");

        Assertions.assertEquals("foo", channel.getSegment(0));
        Assertions.assertEquals("bar", channel.getSegment(1));

        Assertions.assertNull(channel.getSegment(2));
        Assertions.assertNull(channel.getSegment(3));
    }

    @Test
    public void testIsXxx() {
        ChannelId id;

        id = new ChannelId("/foo/bar");
        Assertions.assertFalse(id.isDeepWild());
        Assertions.assertFalse(id.isMeta());
        Assertions.assertFalse(id.isService());
        Assertions.assertFalse(id.isWild());

        id = new ChannelId("/foo/*");
        Assertions.assertTrue(id.isShallowWild());
        Assertions.assertFalse(id.isDeepWild());
        Assertions.assertFalse(id.isMeta());
        Assertions.assertFalse(id.isService());
        Assertions.assertTrue(id.isWild());

        id = new ChannelId("/foo/**");
        Assertions.assertTrue(id.isDeepWild());
        Assertions.assertFalse(id.isMeta());
        Assertions.assertFalse(id.isService());
        Assertions.assertTrue(id.isWild());

        id = new ChannelId("/meta/bar");
        Assertions.assertFalse(id.isDeepWild());
        Assertions.assertTrue(id.isMeta());
        Assertions.assertFalse(id.isService());
        Assertions.assertFalse(id.isWild());

        id = new ChannelId("/service/bar");
        Assertions.assertFalse(id.isDeepWild());
        Assertions.assertFalse(id.isMeta());
        Assertions.assertTrue(id.isService());
        Assertions.assertFalse(id.isWild());

        id = new ChannelId("/service/**");
        Assertions.assertTrue(id.isDeepWild());
        Assertions.assertFalse(id.isMeta());
        Assertions.assertTrue(id.isService());
        Assertions.assertTrue(id.isWild());

        id = new ChannelId("/service/{var}");
        Assertions.assertFalse(id.isMeta());
        Assertions.assertTrue(id.isService());
        Assertions.assertFalse(id.isWild());
        Assertions.assertTrue(id.isTemplate());
    }

    @Test
    public void testStaticIsXxx() {
        Assertions.assertTrue(ChannelId.isMeta("/meta/bar"));
        Assertions.assertFalse(ChannelId.isMeta("/foo/bar"));
        Assertions.assertTrue(ChannelId.isService("/service/bar"));
        Assertions.assertFalse(ChannelId.isService("/foo/bar"));
        Assertions.assertFalse(ChannelId.isMeta("/"));
        Assertions.assertFalse(ChannelId.isService("/"));
    }

    @Test
    public void testIsParent() {
        ChannelId foo = new ChannelId("/foo");
        ChannelId bar = new ChannelId("/bar");
        ChannelId foobar = new ChannelId("/foo/bar");
        ChannelId foobarbaz = new ChannelId("/foo/bar/baz");

        Assertions.assertFalse(foo.isParentOf(foo));
        Assertions.assertTrue(foo.isParentOf(foobar));
        Assertions.assertFalse(foo.isParentOf(foobarbaz));

        Assertions.assertFalse(foobar.isParentOf(foo));
        Assertions.assertFalse(foobar.isParentOf(foobar));
        Assertions.assertTrue(foobar.isParentOf(foobarbaz));

        Assertions.assertFalse(bar.isParentOf(foo));
        Assertions.assertFalse(bar.isParentOf(foobar));
        Assertions.assertFalse(bar.isParentOf(foobarbaz));

        Assertions.assertFalse(foo.isAncestorOf(foo));
        Assertions.assertTrue(foo.isAncestorOf(foobar));
        Assertions.assertTrue(foo.isAncestorOf(foobarbaz));

        Assertions.assertFalse(foobar.isAncestorOf(foo));
        Assertions.assertFalse(foobar.isAncestorOf(foobar));
        Assertions.assertTrue(foobar.isAncestorOf(foobarbaz));

        Assertions.assertFalse(bar.isAncestorOf(foo));
        Assertions.assertFalse(bar.isAncestorOf(foobar));
        Assertions.assertFalse(bar.isAncestorOf(foobarbaz));
    }

    @Test
    public void testEquals() {
        ChannelId foobar0 = new ChannelId("/foo/bar");
        ChannelId foobar1 = new ChannelId("/foo/bar/");
        ChannelId foo = new ChannelId("/foo");
        ChannelId wild = new ChannelId("/foo/*");
        ChannelId deep = new ChannelId("/foo/**");

        Assertions.assertEquals(foobar0, foobar0);
        Assertions.assertEquals(foobar1, foobar0);

        Assertions.assertNotEquals(foo, foobar0);
        Assertions.assertNotEquals(wild, foobar0);
        Assertions.assertNotEquals(deep, foobar0);
    }

    @Test
    public void testMatches() {
        ChannelId foobar0 = new ChannelId("/foo/bar");
        ChannelId foobar1 = new ChannelId("/foo/bar");
        ChannelId foobarbaz = new ChannelId("/foo/bar/baz");
        ChannelId foo = new ChannelId("/foo");
        ChannelId wild = new ChannelId("/foo/*");
        ChannelId deep = new ChannelId("/foo/**");

        Assertions.assertTrue(foobar0.matches(foobar0));
        Assertions.assertTrue(foobar0.matches(foobar1));

        Assertions.assertFalse(foo.matches(foobar0));
        Assertions.assertTrue(wild.matches(foobar0));
        Assertions.assertTrue(deep.matches(foobar0));

        Assertions.assertFalse(foo.matches(foobarbaz));
        Assertions.assertFalse(wild.matches(foobarbaz));
        Assertions.assertTrue(deep.matches(foobarbaz));
    }

    @Test
    public void testWilds() {
        ChannelId id = new ChannelId("/foo/bar/*");
        List<String> wilds = id.getWilds();
        Assertions.assertEquals(0, wilds.size());

        id = new ChannelId("/foo");
        wilds = id.getWilds();
        Assertions.assertEquals(2, wilds.size());
        Assertions.assertEquals("/*", wilds.get(0));
        Assertions.assertEquals("/**", wilds.get(1));

        id = new ChannelId("/foo/bar");
        wilds = id.getWilds();
        Assertions.assertEquals(3, wilds.size());
        Assertions.assertEquals("/foo/*", wilds.get(0));
        Assertions.assertEquals("/foo/**", wilds.get(1));
        Assertions.assertEquals("/**", wilds.get(2));

        id = new ChannelId("/foo/bar/bob");
        wilds = id.getWilds();
        Assertions.assertEquals(4, wilds.size());
        Assertions.assertEquals("/foo/bar/*", wilds.get(0));
        Assertions.assertEquals("/foo/bar/**", wilds.get(1));
        Assertions.assertEquals("/foo/**", wilds.get(2));
        Assertions.assertEquals("/**", wilds.get(3));

        id = new ChannelId("/foo/{bar}");
        wilds = id.getWilds();
        Assertions.assertEquals(3, wilds.size());
        Assertions.assertEquals("/foo/*", wilds.get(0));
        Assertions.assertEquals("/foo/**", wilds.get(1));
        Assertions.assertEquals("/**", wilds.get(2));

        id = new ChannelId("/foo/{bar}/baz");
        wilds = id.getWilds();
        Assertions.assertEquals(2, wilds.size());
        Assertions.assertEquals("/foo/**", wilds.get(0));
        Assertions.assertEquals("/**", wilds.get(1));
    }

    @Test
    public void testInvalid() {
        assertInvalid("/");
        assertInvalid("/foo/*/*");
        assertInvalid("/foo/*/**");
        assertInvalid("/foo/**/**");
        assertInvalid("/foo/**/*");
        assertInvalid("/foo/{var}/*");
        assertInvalid("/foo/*/{var}");
        assertInvalid("/foo/{var1}/{var2}/**");
    }

    @Test
    public void testRegularPart() {
        Assertions.assertEquals("/foo", new ChannelId("/foo/*").getRegularPart());
        Assertions.assertEquals("/foo/bar", new ChannelId("/foo/bar/**").getRegularPart());
        Assertions.assertEquals("/foo", new ChannelId("/foo/{p}").getRegularPart());
        Assertions.assertEquals("/foo/bar", new ChannelId("/foo/bar/{p}").getRegularPart());
        Assertions.assertEquals("/foo", new ChannelId("/foo/{p1}/{p2}").getRegularPart());
        Assertions.assertNull(new ChannelId("/*").getRegularPart());
        Assertions.assertNull(new ChannelId("/**").getRegularPart());
        Assertions.assertNull(new ChannelId("/{p}").getRegularPart());
        Assertions.assertNull(new ChannelId("/{p1}/{p2}").getRegularPart());
    }

    private void assertInvalid(String channel) {
        try {
            // Call depth() to ensure the ChannelId is resolved.
            new ChannelId(channel).depth();
            Assertions.fail(channel);
        } catch (IllegalArgumentException x) {
            // Expected
        }
    }
}
