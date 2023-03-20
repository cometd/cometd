package ${package};

import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

@Named
@Singleton
@Service("helloService")
public class HelloService {
    @Inject
    private BayeuxServer bayeux;
    @Session
    private ServerSession serverSession;

    @PostConstruct
    public void init() {
    }

    @Listener("/service/hello")
    public void processHello(ServerSession remote, ServerMessage message) {
        Map<String, Object> input = message.getDataAsMap();
        String name = (String)input.get("name");

        Map<String, Object> output = new HashMap<>();
        output.put("greeting", "Hello, " + name);
        remote.deliver(serverSession, "/hello", output, Promise.noop());
    }
}
