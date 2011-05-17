package org.cometd.server.policy;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Default {@link org.cometd.bayeux.server.SecurityPolicy} which determines if a user can handshake
 * by checking if the HttpSession contains the required attribute.
 * <p/>
 * I.e. useful in any J2ee or custom-based authentication
 *
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
public class SessionBasedSecurityPolicy extends AuthSecurityPolicy {
    private final String attributeName;
    private final Set<String> excludes;

    /**
     * @param attributeName The session attribute name to check for existance
     * @param excludes      Optional list of ames to match as being non authenticated. I.e. 'anonymous', 'guest', ...
     */
    public SessionBasedSecurityPolicy(String attributeName, String... excludes) {
        this.attributeName = attributeName;
        this.excludes = new HashSet<String>(Arrays.asList(excludes));
    }

    @Override
    protected boolean isAuthenticated(BayeuxServer server, ServerSession session, ServerMessage message) {
        return server.getContext().getHttpSessionAttribute(attributeName) != null
                && !excludes.contains(server.getContext().getHttpSessionAttribute(attributeName).toString());
    }
}
