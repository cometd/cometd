package org.cometd.bayeux.server;

import java.util.EventListener;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Message;

public interface BayeuxServer extends Bayeux
{
    void addSessionListener(SessionBayeuxListener listener);
    LocalSession newLocalSession(String idHint);
    ServerChannel getChannel(String channelId);
    
    void publish(Message message);

    public SecurityPolicy getSecurityPolicy();
    public void setSecurityPolicy(SecurityPolicy securityPolicy);
    public void addListener(Listener listener);
    
    interface Listener extends EventListener
    {};
}
