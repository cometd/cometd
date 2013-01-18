#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
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

package ${package};

import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;

@Named
@Singleton
@Service("helloService")
public class HelloService
{
    @Inject
    private BayeuxServer bayeux;
    @Session
    private ServerSession serverSession;

    @PostConstruct
    public void init()
    {
    }

    @Listener("/service/hello")
    public void processHello(ServerSession remote, ServerMessage.Mutable message)
    {
        Map<String, Object> input = message.getDataAsMap();
        String name = (String)input.get("name");

        Map<String, Object> output = new HashMap<String, Object>();
        output.put("greeting", "Hello, " + name);
        remote.deliver(serverSession, "/hello", output, null);
    }
}
