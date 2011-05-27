package org.cometd.server.policy;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Default {@link org.cometd.bayeux.server.SecurityPolicy} which determines if a user can handshake
 * by checking if the request has a {@link java.security.Principal}.
 * <p/>
 * I.e. useful in any J2ee or custom-based authentication
 * <p/>
 * You can also set a list of user principal names to exclude (like 'anonymous' or other sorts)
 *
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
public class J2eeBasedSecurityPolicy extends AuthSecurityPolicy {
    private final Set<String> excludes;

    /**
     * @param excludes Optional list of principal names to match as being non authenticated. I.e. 'anonymous', 'guest', ...
     */
    public J2eeBasedSecurityPolicy(String... excludes) {
        this.excludes = new HashSet<String>(Arrays.asList(excludes));
    }

    @Override
    protected boolean isAuthenticated(BayeuxServer server, ServerSession session, ServerMessage message) {
        return server.getContext().getUserPrincipal() != null && !excludes.contains(server.getContext().getUserPrincipal().getName());
    }
}
