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
package org.cometd.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>For server-side services, identifies callback methods that are invoked
 * when a message is processed on local-side.</p>
 * <p>For client-side services, identifies callback methods that are invoked
 * with the same semantic of
 * {@code org.cometd.bayeux.client.ClientSessionChannel#subscribe(ClientSessionChannel.MessageListener)}.</p>
 *
 * @see Listener
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Subscription {
    /**
     * @return the list of channels to which the callback method subscribes to
     */
    String[] value();
}
