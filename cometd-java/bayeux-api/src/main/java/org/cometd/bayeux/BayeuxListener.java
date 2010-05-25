package org.cometd.bayeux;

/**
 * <p>The common base interface for Bayeux listeners.</p>
 * <p>Specific sub-interfaces define what kind of events listeners will be notified.</p>
 * @deprecated Use {@link Bayeux.BayeuxListener} instead
 */
@Deprecated
public interface BayeuxListener extends Bayeux.BayeuxListener
{
}
