#set($symbol_pound='#')
#set($symbol_dollar='$')
#set($symbol_escape='\')

package ${package};

import java.util.HashMap;
import java.util.Map;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;

public class HelloService extends AbstractService {
    public HelloService(BayeuxServer bayeux) {
        super(bayeux, "hello");
        addService("/service/hello", "processHello");
    }

    public void processHello(ServerSession remote, ServerMessage message) {
        Map<String, Object> input = message.getDataAsMap();
        String name = (String)input.get("name");

        Map<String, Object> output = new HashMap<>();
        output.put("greeting", "Hello, " + name);
        remote.deliver(getServerSession(), "/hello", output);
    }
}
