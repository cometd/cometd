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

package org.mortbay.cometd;

import junit.framework.TestCase;

public class ChannelIdTest extends TestCase
{

    public void testChannelPattern()
    {
        ChannelId id;

        id=new ChannelId("/");
        assertEquals(0,id.depth());
        assertFalse(id.isWild());
        assertTrue(id.matches("/"));

        id=new ChannelId("/test");
        assertEquals(1,id.depth());
        assertEquals("test",id.getSegment(0));
        assertFalse(id.isWild());
        assertTrue(id.matches("/test"));
        
        id=new ChannelId("/test/abc");
        assertEquals(2,id.depth());
        assertEquals("test",id.getSegment(0));
        assertEquals("abc",id.getSegment(1));
        assertFalse(id.isWild());
        assertTrue(id.matches("/test/abc"));
        assertFalse(id.matches("/test/abc/more"));
        assertFalse(id.matches("/test/ab"));
        assertFalse(id.matches("/abc"));
        assertFalse(id.matches(""));
        
        id=new ChannelId("/test/*");
        assertEquals(2,id.depth());
        assertEquals("test",id.getSegment(0));
        assertEquals("*",id.getSegment(1));
        assertTrue(id.isWild());
        assertTrue(id.matches("/test/a"));
        assertTrue(id.matches("/test/abc"));
        assertFalse(id.matches("/test/abc/foo"));
        assertFalse(id.matches("/tost/abc"));
        assertFalse(id.matches("/test"));
        
        id=new ChannelId("/test/a*");
        assertFalse(id.matches("/test/ac"));
        assertTrue(id.matches("/test/a*"));
        
        id=new ChannelId("/test/a*c");
        assertFalse(id.matches("/test/ac"));
        assertTrue(id.matches("/test/a*c"));
        
        id=new ChannelId("/test/*/foo");
        assertTrue(id.matches("/test/*/foo"));
        assertFalse(id.matches("/test/blah/foo"));
        
        id=new ChannelId("/test/**/foo");
        assertTrue(id.matches("/test/**/foo"));
        assertFalse(id.matches("/test/abc/foo"));
        

        id=new ChannelId("/test/*");
        assertFalse(id.matches("/test"));
        assertTrue(id.matches("/test/foo"));
        assertFalse(id.matches("/test/abc/foo"));
        assertFalse(id.matches("/test/abc/def/foo"));
        
        id=new ChannelId("/test/**");
        assertFalse(id.matches("/test"));
        assertTrue(id.matches("/test/foo"));
        assertTrue(id.matches("/test/abc/foo"));
        assertTrue(id.matches("/test/abc/def/foo"));
        
        
    }
}
