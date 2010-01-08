package org.cometd.common;

import junit.framework.Assert;

import org.junit.Test;


public class TestChannelId extends Assert
{

    @Test
    public void testDepth()
    {
        assertEquals(0,new ChannelId("/").depth());
        assertEquals(1,new ChannelId("/foo").depth());
        assertEquals(1,new ChannelId("/foo/").depth());
        assertEquals(2,new ChannelId("/foo/bar").depth());
        assertEquals(2,new ChannelId("/foo/bar/").depth());
        assertEquals(3,new ChannelId("/foo/bar/*").depth());
        assertEquals(3,new ChannelId("/foo/bar/**").depth());
    }

    @Test
    public void testSegments()
    {
        ChannelId channel = new ChannelId("/foo/bar");
        
        Assert.assertEquals("foo",channel.getSegment(0));
        Assert.assertEquals("bar",channel.getSegment(1));
        
        try
        {
            channel.getSegment(2);
            assertFalse(true);
        }
        catch(ArrayIndexOutOfBoundsException e)
        {
            assertTrue(true);
        }
    }

    @Test
    public void testIsXxx()
    {
        ChannelId id = new ChannelId("/");
        assertFalse(id.isDeepWild());
        assertFalse(id.isMeta());
        assertFalse(id.isService());
        assertFalse(id.isWild());
        
        id = new ChannelId("/foo/bar");
        assertFalse(id.isDeepWild());
        assertFalse(id.isMeta());
        assertFalse(id.isService());
        assertFalse(id.isWild());
        
        id = new ChannelId("/foo/*");
        assertFalse(id.isDeepWild());
        assertFalse(id.isMeta());
        assertFalse(id.isService());
        assertTrue(id.isWild());
        
        id = new ChannelId("/foo/**");
        assertTrue(id.isDeepWild());
        assertFalse(id.isMeta());
        assertFalse(id.isService());
        assertTrue(id.isWild());
        
        id = new ChannelId("/meta/bar");
        assertFalse(id.isDeepWild());
        assertTrue(id.isMeta());
        assertFalse(id.isService());
        assertFalse(id.isWild());
        
        id = new ChannelId("/service/bar");
        assertFalse(id.isDeepWild());
        assertFalse(id.isMeta());
        assertTrue(id.isService());
        assertFalse(id.isWild());

        id = new ChannelId("/service/**");
        assertTrue(id.isDeepWild());
        assertFalse(id.isMeta());
        assertTrue(id.isService());
        assertTrue(id.isWild());
    }   
    
    @Test
    public void testStaticIsXxx()
    { 
        assertTrue(ChannelId.isMeta("/meta/bar"));
        assertFalse(ChannelId.isMeta("/foo/bar"));
        assertTrue(ChannelId.isService("/service/bar"));
        assertFalse(ChannelId.isService("/foo/bar"));
        assertFalse(ChannelId.isMeta("/"));
        assertFalse(ChannelId.isService("/"));
    }
    
    @Test
    public void testIsParent()
    { 
        ChannelId root = new ChannelId("/");
        ChannelId foo = new ChannelId("/foo");
        ChannelId bar = new ChannelId("/bar");
        ChannelId foobar = new ChannelId("/foo/bar");
        ChannelId foobarbaz = new ChannelId("/foo/bar/baz");
        
        assertTrue(root.isParentOf(foo));
        assertTrue(root.isParentOf(foobar));
        assertTrue(root.isParentOf(foobarbaz));
        
        assertFalse(foo.isParentOf(foo));
        assertTrue(foo.isParentOf(foobar));
        assertTrue(foo.isParentOf(foobarbaz));
        
        assertFalse(foobar.isParentOf(foo));
        assertFalse(foobar.isParentOf(foobar));
        assertTrue(foobar.isParentOf(foobarbaz));
        
        assertFalse(bar.isParentOf(foo));
        assertFalse(bar.isParentOf(foobar));
        assertFalse(bar.isParentOf(foobarbaz));
        
    }
    
    @Test
    public void testEquals()
    { 
        ChannelId foobar0 = new ChannelId("/foo/bar");
        ChannelId foobar1 = new ChannelId("/foo/bar");
        ChannelId foo = new ChannelId("/foo");
        ChannelId wild = new ChannelId("/foo/*");
        ChannelId deep = new ChannelId("/foo/**");
        
        assertTrue(foobar0.equals(foobar0));
        assertTrue(foobar0.equals(foobar1));
        
        assertFalse(foobar0.equals(foo));
        assertFalse(foobar0.equals(wild));
        assertFalse(foobar0.equals(deep));
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
        
        assertTrue(foobar0.matches(foobar0));
        assertTrue(foobar0.matches(foobar1));
        
        assertFalse(foo.matches(foobar0));
        assertTrue(wild.matches(foobar0));
        assertTrue(deep.matches(foobar0));
        
        assertFalse(foo.matches(foobarbaz));
        assertFalse(wild.matches(foobarbaz));
        assertTrue(deep.matches(foobarbaz));
        
    }
        
        
}
