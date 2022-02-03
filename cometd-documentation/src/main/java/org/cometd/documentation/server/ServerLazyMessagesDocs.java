/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.documentation.server;

import javax.inject.Inject;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.server.Configure;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;

@SuppressWarnings("unused")
public class ServerLazyMessagesDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::lazyMessage[]
    @Service
    public class LazyMessageService {
        @Inject
        private BayeuxServer bayeuxServer;
        @Session
        private LocalSession session;

        public void receiveNewsFromExternalSystem(NewsInfo news) {
            ServerMessage.Mutable message = bayeuxServer.newMessage();
            message.setChannel("/news");
            message.setData(news);
            message.setLazy(true);
            bayeuxServer.getChannel("/news").publish(session, message, Promise.noop());
        }
    }
    // end::lazyMessage[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::lazyChannel[]
    @Service
    public class LazyChannelService {
        @Inject
        private BayeuxServer bayeuxServer;
        @Session
        private LocalSession session;

        @Configure("/news")
        public void setupNewsChannel(ConfigurableServerChannel channel) {
            channel.setLazy(true);
        }

        public void receiveNewsFromExternalSystem(NewsInfo news) {
            bayeuxServer.getChannel("/news").publish(session, news, Promise.noop());
        }
    }
    // end::lazyChannel[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::lazyTimeout[]
    @Service
    public class LazyService {
        @Configure("/news")
        public void setupNewsChannel(ConfigurableServerChannel channel) {
            channel.setLazy(true);
        }

        @Configure("/chat")
        public void setupChatChannel(ConfigurableServerChannel channel) {
            channel.setLazyTimeout(2000);
        }
    }
    // end::lazyTimeout[]

    private static class NewsInfo {
    }
}
