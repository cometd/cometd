package org.cometd.bayeux;

public interface BayeuxServer extends Bayeux
{
    void addSessionListener(SessionListener listener);
    LocalSession newLocalSession(String idHint);
    ServerChannel getChannel(String channelId);
    
    void publish(Message message);
    void publishLazy(Message message);

    public SecurityPolicy getSecurityPolicy();
    public void setSecurityPolicy(SecurityPolicy securityPolicy);
}
