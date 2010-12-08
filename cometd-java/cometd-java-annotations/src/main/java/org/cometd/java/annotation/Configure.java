package org.cometd.java.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ConfigurableServerChannel;

/**
 * <p>For server-side services, identifies channel configuration methods that are invoked
 * when a message is processed on server-side. The methods must have the same signature as 
 * {@link ConfigurableServerChannel.Initializer#configureChannel(ConfigurableServerChannel)}
 * </p>
 *
 * @see Subscription
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Configure
{
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
