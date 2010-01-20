#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package};

import java.util.Map;
import java.util.HashMap;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.server.BayeuxService;

public class HelloService extends BayeuxService
{
    public HelloService(Bayeux bayeux)
    {
        super(bayeux, "hello");
        subscribe("/service/hello", "processHello");
    }

    public void processHello(Client remote, Message message)
    {
        Map<String, Object> input = (Map<String, Object>)message.getData();
        String name = (String)input.get("name");

        Map<String, Object> output = new HashMap<String, Object>();
        output.put("greeting", "Hello, " + name);
        remote.deliver(getClient(), "/hello", output, null);
    }
}
