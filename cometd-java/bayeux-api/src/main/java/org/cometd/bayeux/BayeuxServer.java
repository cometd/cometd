package org.cometd.bayeux;

import java.util.EventListener;

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
