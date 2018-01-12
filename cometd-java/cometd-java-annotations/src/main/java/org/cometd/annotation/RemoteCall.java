/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.cometd.bayeux.server.ServerSession;

/**
 * <p>Identifies callback methods on server-side services that are invoked
 * when the client is performing a remote call.</p>
 * <p>Methods annotated with this annotation must have the following
 * signature:</p>
 * <pre>
 * &#64;Service
 * public class RemoteService
 * {
 *     &#64;Session
 *     private LocalSession sender;
 *
 *     &#64;RemoteCall("/foo")
 *     public void service(RemoteCall.Caller caller, Object data)
 *     {
 *         call.result(sender, process(data));
 *     }
 * }
 * </pre>
 *
 * @see RemoteCall.Caller
 * @see Listener
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RemoteCall {
    /**
     * <p>Returns the list of remote call target strings used by clients to
     * identify the target of remote calls.</p>
     * <p>The target strings are interpreted as a channel string and therefore
     * may only contain characters valid for a channel; they may contain
     * arguments (for example: &#64;RemoteCall("/foo/{p}"), but they may not
     * be wildcard channels.</p>
     *
     * @return the list of remote call targets to which the annotated method responds to
     */
    public String[] value();

    /**
     * <p>Objects implementing this interface represent remote clients that
     * performed remote calls.</p>
     * <p>Server-side applications may return a result or a failure via, respectively,
     * {@link #result(Object)} and {@link #failure(Object)}.</p>
     */
    public interface Caller {
        /**
         * @return the {@link ServerSession} associated with this remote caller
         */
        public ServerSession getServerSession();

        /**
         * <p>Returns the given {@code result} to the remote caller.</p>
         *
         * @param result the result to send to the remote caller
         * @return true if the result was sent to the remote client, false otherwise
         */
        public boolean result(Object result);

        /**
         * <p>Returns the given {@code failure} to the remote caller.</p>
         *
         * @param failure the failure to send to the remote caller
         * @return true if the failure was sent to the remote client, false otherwise
         */
        public boolean failure(Object failure);
    }
}
