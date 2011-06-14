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

package org.cometd.java.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Identifies classes whose instances are services that interact with the Bayeux API.</p>
 * <p>A service can register callback methods by annotating them with {@link Listener} or
 * with {@link Subscription}.</p>
 * <p>Service objects are configured by {@link ServerAnnotationProcessor}s or by {@link ClientAnnotationProcessor}s.</p>
 * <p>Services can have an optional name that is used as a prefix for the {@link org.cometd.bayeux.Session#getId() session identifier},
 * thus helping in debug and logging.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Service
{
    /**
     * @return The name of this service
     */
    String value() default "";
}
