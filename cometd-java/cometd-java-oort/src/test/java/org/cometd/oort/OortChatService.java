/**
 *
 */
package org.cometd.oort;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.inject.Inject;
import javax.servlet.ServletContext;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.java.annotation.Configure;
import org.cometd.java.annotation.Listener;
import org.cometd.java.annotation.Service;
import org.cometd.java.annotation.Session;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.filter.DataFilter;
import org.cometd.server.filter.DataFilterMessageListener;
import org.cometd.server.filter.JSONDataFilter;
import org.cometd.server.filter.NoMarkupFilter;

@Service("chat")
public class OortChatService
{
    private final ConcurrentMap<String, Set<String>> _members = new ConcurrentHashMap<String, Set<String>>();
    @Inject
    private BayeuxServer _bayeux;
    @Session
    private ServerSession _session;
    private Oort _oort;
    private Seti _seti;

    OortChatService(ServletContext context)
    {
        _oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        if (_oort==null)
            throw new RuntimeException("!"+ Oort.OORT_ATTRIBUTE);
        _seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);
        if (_seti==null)
            throw new RuntimeException("!"+Seti.SETI_ATTRIBUTE);

        _oort.observeChannel("/chat/**");
        _oort.observeChannel("/members/**");
    }

    @SuppressWarnings("unused")
    @Configure ({"/chat/**","/members/**"})
    private void configureChatStarStar(ConfigurableServerChannel channel)
    {
        final DataFilterMessageListener noMarkup = new DataFilterMessageListener(_bayeux,new NoMarkupFilter(),new BadWordFilter());
        channel.addListener(noMarkup);
        channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
    }

    @SuppressWarnings("unused")
    @Configure ("/service/privatechat")
    private void configurePrivateChat(ConfigurableServerChannel channel)
    {
        final DataFilterMessageListener noMarkup = new DataFilterMessageListener(_bayeux,new NoMarkupFilter(),new BadWordFilter());
        channel.setPersistent(true);
        channel.addListener(noMarkup);
        channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
    }

    @SuppressWarnings("unused")
    @Configure ("/service/members")
    private void configureMembers(ConfigurableServerChannel channel)
    {
        channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
        channel.setPersistent(true);
    }

    private Set<String> getMemberList(String room)
    {
        Set<String> members = _members.get(room);
        if (members == null)
        {
            Set<String> newMembers = new HashSet<String>();
            members = _members.putIfAbsent(room, newMembers);
            if (members == null) members = newMembers;
        }
        return members;
    }

    @Listener("/service/members")
    public void handleMembership(final ServerSession client, ServerMessage message)
    {
        Map<String, Object> data = message.getDataAsMap();
        final String room = ((String)data.get("room")).substring("/chat/".length());
        final String userName = (String)data.get("user");

        final Set<String> members = getMemberList(room);
        synchronized (members)
        {
            members.add(userName);
            client.addListener(new ServerSession.RemoveListener()
            {
                public void removed(ServerSession session, boolean timeout)
                {
                    if (!_oort.isOort(client))
                        _seti.disassociate(userName);
                    members.remove(userName);
                    broadcastMembers(room,members);
                }
            });

            if (!_oort.isOort(client))
                _seti.associate(userName,client);

            broadcastMembers(room,members);
        }
    }

    @Listener("/members/**")
    public void handleMembershipBroadcast(final ServerSession client, ServerMessage message)
    {
        String room=message.getChannel().substring("/members/".length());
        Object[] newMembers = (Object[])message.getData();

        final Collection<String> members = getMemberList(room);
        synchronized (members)
        {
            boolean added=false;
            for (Object o : newMembers)
                added|=members.add(o.toString());

            if (added)
                broadcastMembers(room,members);
        }
    }

    private void broadcastMembers(String room,Collection<String> members)
    {
        // Broadcast the new members list
        ClientSessionChannel channel = _session.getLocalSession().getChannel("/members/"+room);
        channel.publish(members);
    }

    @Listener("/service/privatechat")
    public void privateChat(ServerSession client, ServerMessage message)
    {
        Map<String,Object> data = message.getDataAsMap();
        String toUid=(String)data.get("peer");
        String toChannel=(String)data.get("room");
        data.put("scope","private");
        data.put("user",data.get("user")+"->"+toUid);
        client.deliver(client,toChannel,data,message.getId());
        _seti.sendMessage(toUid,toChannel,data);
    }

    class BadWordFilter extends JSONDataFilter
    {
        @Override
        protected Object filterString(String string)
        {
            if (string.indexOf("dang")>=0)
                throw new DataFilter.Abort();
            return string;
        }
    }
}

