package org.cometd.bayeux;

import java.util.Set;

public interface BayeuxServer extends Bayeux
{
    Set<ServerChannel> getChannels();
    Set<ServerSession> getSessions();
    
    void addSessionListener(SessionListener listener);
    
    LocalSession newLocalSession(String idHint);
    
    ServerChannel getChannel(String channelId);
    
    void publish(Message message);
    void publishLazy(Message message);
    

    public SecurityPolicy getSecurityPolicy();

    public void setSecurityPolicy(SecurityPolicy securityPolicy);

}
