/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.annotation.server;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.cometd.bayeux.server.ConfigurableServerChannel;

/**
 * <p>For server-side services, identifies channel configuration methods that are invoked
 * when a message is processed on server-side. The methods must have the same signature as
 * {@link ConfigurableServerChannel.Initializer#configureChannel(ConfigurableServerChannel)}
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Configure {
    /**
     * @return the list of channels which are initialised
     */
    String[] value();

    /**
     * @return if true, then an IllegalStateException is thrown if the channel already exists
     */
    boolean errorIfExists() default true;

    /**
     * @return if true, then the configuration method is called even if it already exists
     */
    boolean configureIfExists() default false;

}
