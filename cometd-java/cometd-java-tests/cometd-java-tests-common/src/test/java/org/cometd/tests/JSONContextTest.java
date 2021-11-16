package org.cometd.tests;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.JettyJSONContextServer;
import org.eclipse.jetty.util.ajax.JSONPojoConvertor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class JSONContextTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testCustomSerialization(Transport transport) throws Exception {
        Map<String, String> serverOptions = serverOptions(transport);
        serverOptions.put(AbstractServerTransport.JSON_CONTEXT_OPTION, CustomJSONContextServer.class.getName());
        startServer(transport, serverOptions);

        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.JSON_CONTEXT_OPTION, new CustomJSONContextClient());
        BayeuxClient client = new BayeuxClient(cometdURL, newClientTransport(transport, clientOptions));

        List<Long> userIds = Arrays.asList(1L, 2L);
        CountDownLatch messageLatch = new CountDownLatch(1);
        client.handshake(hsReply -> {
            ClientSessionChannel channel = client.getChannel("/custom");
            channel.subscribe((c, m) -> {
                Users users = (Users)m.getData();
                Assertions.assertEquals(userIds, users.getUserIds());
                messageLatch.countDown();
            });
            channel.publish(new Users(userIds));
        });

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    public static class Users {
        private List<Long> userIds;

        public Users() {
        }

        public Users(List<Long> userIds) {
            setUserIds(userIds);
        }

        public List<Long> getUserIds() {
            return userIds;
        }

        public void setUserIds(List<Long> userIds) {
            this.userIds = userIds;
        }
    }

    // Parsing a JSON array produces a List in all cases.
    // AsyncJSON retains the list as the representation of a JSON array.
    // JSON converts the parsed list in a Java array, so it must be configured to retain the list.
    public static class CustomJSONContextServer extends JettyJSONContextServer {
        public CustomJSONContextServer() {
            putConvertor(Users.class.getName(), new JSONPojoConvertor(Users.class));
            getJSON().setArrayConverter(list -> list);
        }
    }

    public static class CustomJSONContextClient extends JettyJSONContextClient {
        public CustomJSONContextClient() {
            putConvertor(Users.class.getName(), new JSONPojoConvertor(Users.class));
            getJSON().setArrayConverter(list -> list);
        }
    }
}
