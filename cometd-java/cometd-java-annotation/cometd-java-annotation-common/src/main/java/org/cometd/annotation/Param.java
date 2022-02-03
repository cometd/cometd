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
package org.cometd.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>For methods annotated with {@link Listener} or {@link Subscription}
 * whose value is a channel template such as {@code /game/{gameId}}, this
 * annotation binds parameters of those methods with the value obtained
 * matching the actual channel with the channel template.</p>
 * <p>For example:</p>
 * <pre>
 * &#64;Service
 * public class GameService
 * {
 *     &#64;Listener("/game/{gameId}")
 *     public void handleGame(ServerSession remote, ServerMessage.Mutable message, &#64;Param("gameId") String gameId)
 *     {
 *         // Use the 'gameId' parameter here.
 *     }
 * }
 * </pre>
 * <p>The variable name defined in the {@link Listener} or {@link Subscription}
 * annotation must be the same defined by the {@link Param} annotation.</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Param {
    /**
     * @return the variable name that identifies the parameter annotated with this annotation
     */
    public String value();
}
