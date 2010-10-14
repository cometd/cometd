package org.cometd.java.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.cometd.bayeux.client.ClientSessionChannel;

/**
 * <p>For server-side services, identifies callback methods that are invoked
 * when a message is processed on server-side.</p>
 * <p>For client-side services, identifies callback methods that are invoked
 * with the same semantic of {@link ClientSessionChannel.MessageListener}.</p>
 *
 * @see Subscription
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Listener
{
    /**
     * @return the list of channels to which the callback method listens to
     */
    String[] value();

    /**
     * @return whether the callback method should receive messages that has itself published
     * on channels that would trigger the invocation of callback method
     */
    boolean receiveOwnPublishes() default false;
}
