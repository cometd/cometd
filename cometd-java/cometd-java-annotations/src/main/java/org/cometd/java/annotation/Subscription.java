package org.cometd.java.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.cometd.bayeux.client.ClientSessionChannel;

/**
 * <p>For server-side services, identifies callback methods that are invoked
 * when a message is processed on local-side.</p>
 * <p>For client-side services, identifies callback methods that are invoked
 * with the same semantic of {@link ClientSessionChannel#subscribe(ClientSessionChannel.MessageListener)}.</p>
 *
 * @see Listener
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Subscription
{
    /**
     * @return the list of channels to which the callback method subscribes to
     */
    String[] value();
}
