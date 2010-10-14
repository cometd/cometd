package org.cometd.java.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Identifies fields and methods that can be injected with {@link org.cometd.bayeux.Session sessions} objects
 * scoped to the service instance.</p>
 * <p>On server-side services it's typical to inject {@link ServerSession} or {@link LocalSession}, while on
 * client-side it is possible to inject {@link ClientSession}.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Session
{
}
