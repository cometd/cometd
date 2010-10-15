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
