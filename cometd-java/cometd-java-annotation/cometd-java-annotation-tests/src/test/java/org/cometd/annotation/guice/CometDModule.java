/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.annotation.guice;

import jakarta.inject.Singleton;
import com.google.inject.AbstractModule;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.BayeuxServerImpl;

public class CometDModule extends AbstractModule {
    @Override
    protected void configure() {
        // Binds but does not call start() yet, since it's not good practice in modules
        // (modules do not have a lifecycle: cannot be stopped)

        // Specifies that the implementation class must be a singleton.
        // This is needed in case a dependency declares to depend on
        // BayeuxServerImpl instead of BayeuxServer so that Guice does
        // not create one instance for BayeuxServer dependencies and
        // one instance for BayeuxServerImpl dependencies.
        bind(BayeuxServerImpl.class).in(Singleton.class);
        // Binds the interface to the implementation class
        bind(BayeuxServer.class).to(BayeuxServerImpl.class);

        // Services do not need to be configured here, since
        // they are normally not injected anywhere, but just instantiated
        // via Injector.getInstance()
    }
}
