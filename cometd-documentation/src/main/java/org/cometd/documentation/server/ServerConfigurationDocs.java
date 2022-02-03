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

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.server.BayeuxServerImpl;

@SuppressWarnings("unused")
public class ServerConfigurationDocs {
    public void init() {
        BayeuxServer bayeuxServer = new BayeuxServerImpl();
        // tag::init[]
        // Create a channel atomically.
        MarkedReference<ServerChannel> channelRef = bayeuxServer.createChannelIfAbsent("/my/channel", new ServerChannel.Initializer() {
            public void configureChannel(ConfigurableServerChannel channel) {
                // Here configure the channel
            }
        });
        // end::init[]
    }

    public void persist() {
        BayeuxServer bayeuxServer = new BayeuxServerImpl();
        // tag::persist[]
        MarkedReference<ServerChannel> channelRef = bayeuxServer.createChannelIfAbsent("/my/channel", channel -> channel.setPersistent(true));
        // end::persist[]
    }

    public void reference() {
        BayeuxServer bayeuxServer = new BayeuxServerImpl();
        // tag::reference[]
        String channelName = "/my/channel";
        MarkedReference<ServerChannel> channelRef = bayeuxServer.createChannelIfAbsent(channelName, channel -> channel.setPersistent(true));

        // Was the channel created atomically by this thread?
        boolean created = channelRef.isMarked();

        // Guaranteed to never be null: either it's the channel
        // just created, or it has been created concurrently
        // by some other thread.
        ServerChannel channel = channelRef.getReference();
        // end::reference[]
    }

    public void wrong() {
        BayeuxServer bayeuxServer = new BayeuxServerImpl();
        // tag::wrong[]
        String channelName = "/my/channel";

        // Wrong, channel not marked as persistent, but used later.
        bayeuxServer.createChannelIfAbsent(channelName);

        // Other application code here, the channel may be swept.

        // Get the channel without creating it, it may have been swept.
        ServerChannel channel = bayeuxServer.getChannel(channelName);

        // May throw NullPointerException because channel == null.
        ChannelId channelId = channel.getChannelId();
        // end::wrong[]
    }
}
