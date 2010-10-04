package org.cometd.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Retention;
import java.lang.annotation.Documented;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;

/**
 * Inject a Cometd service.
 * <p>
 * This annotation is used to inject field or methods values into a Cometd service pojo.  
 * Fields or setter methods of the following types can be injected:<ul>
 * <li>{@link BayeuxServer}</li>
 * <li>{@link ServerSession}</li>
 * <li>{@link LocalSession}</li>
 * <li>{@link ThreadPool}</li>
 * </ul>
 * Note that specific Implementation types for these interfaces may also 
 * be injected (eg QueuedThreadPool), but will result in a non portable service.
 * <p>
 * This annotation will eventually be deprecated in favour of @Inject for JSR330.
 */
@Target({ElementType.FIELD,ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Inject
{}
