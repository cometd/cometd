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
package org.cometd.documentation.oort;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.cometd.annotation.Service;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.oort.Oort;
import org.cometd.oort.OortService;

@SuppressWarnings("unused")
public class OortServiceDocs {
    // tag::create[]
    public void createOortService(ServerAnnotationProcessor processor, Oort oort) {
        // Create the service instance and process its annotations.
        NameEditService nameEditService = new NameEditService(oort);
        processor.process(nameEditService);
    }
    // end::create[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::service[]
    @Service(NameEditService.NAME)
    public class NameEditService extends OortService<Boolean, OortService.ServerContext> {
        public static final String NAME = "name_edit";

        public NameEditService(Oort oort) {
            super(oort, NAME);
        }

        // Lifecycle methods triggered by standard lifecycle annotations.

        @PostConstruct
        public void construct() throws Exception {
            start();
        }

        @PreDestroy
        public void destroy() throws Exception {
            stop();
        }

        // CometD's listener method on channel "/service/edit"

        @org.cometd.annotation.Listener("/service/edit")
        public void editName(ServerSession remote, ServerMessage message) {
            // Step #1: remote client sends an action request.
            // This code runs in the requesting node.

            // Find the owner Oort URL from the message.
            // Applications must implement this method with their logic.
            String ownerURL = findOwnerURL(message);

            // Prepare the action data.
            String oldName = (String)remote.getAttribute("name");
            String newName = (String)message.getDataAsMap().get("name");
            Map<String, Object> actionData = new HashMap<>();
            actionData.put("oldName", oldName);
            actionData.put("newName", newName);

            // Step #2: forward to the owner node.
            // Method forward(...) is inherited from OortService.
            forward(ownerURL, actionData, new ServerContext(remote, message));
        }

        @Override
        protected Result<Boolean> onForward(Request request) {
            // Step #3: perform the action.
            // This runs in the owner node.

            try {
                Map<String, Object> actionData = request.getDataAsMap();
                // Edit the name.
                // Applications must implement this method.
                boolean result = updateName(actionData);

                // Return the action result.
                return Result.success(result);
            } catch (Exception x) {
                // Return the action failure.
                return Result.failure("Could not edit name, reason: " + x.getMessage());
            }
        }

        @Override
        protected void onForwardSucceeded(Boolean result, ServerContext context) {
            // Step #4: successful result.
            // This runs in the requesting node.

            // Notify the remote client of the result of the edit.
            context.getServerSession().deliver(getLocalSession(), context.getServerMessage().getChannel(), result, Promise.noop());
        }

        @Override
        protected void onForwardFailed(Object failure, ServerContext context) {
            // Step #5: failure result.
            // This runs in the requesting node.

            // Notify the remote client of the failure.
            context.getServerSession().deliver(getLocalSession(), context.getServerMessage().getChannel(), failure, Promise.noop());
        }
    }
    // end::service[]

    private String findOwnerURL(ServerMessage message) {
        return toString();
    }

    private boolean updateName(Map<String, Object> actionData) {
        return true;
    }
}
