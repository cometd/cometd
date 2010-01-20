package org.cometd.server;

import junit.framework.TestCase;

import org.cometd.Message;

public class MessagePoolTest extends TestCase
{
    String test="[{\"id\":\"1197508701252\",\"data\":{\"user\":\"Client0002\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 2\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"},{\"id\":\"1197508701253\",\"data\":{\"user\":\"Client0003\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 3\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"},{\"id\":\"1197508701254\",\"data\":{\"user\":\"Client0004\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 4\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"},{\"id\":\"1197508701256\",\"data\":{\"user\":\"Client0005\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 5\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"},{\"id\":\"1197508701257\",\"data\":{\"user\":\"Client0006\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 6\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"},{\"id\":\"1197508701258\",\"data\":{\"user\":\"Client0007\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 7\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"},{\"id\":\"1197508701260\",\"data\":{\"user\":\"Client0008\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 8\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"},{\"id\":\"1197508701263\",\"data\":{\"user\":\"Client0009\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 9\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"},{\"id\":\"1197508701268\",\"data\":{\"user\":\"Client0010\",\"chat\":\"xxxxxxxxxxxxxxxxxxxx 10\"},\"channel\":\"/chat/demo\",\"clientId\":\"1xl73fat6x0ft\"}];";

    public void testParse() throws Exception
    {
        MessagePool pool = new MessagePool();
        Message[] messages = pool.parse(test);
        assertEquals(9,messages.length);
    }
    
}
