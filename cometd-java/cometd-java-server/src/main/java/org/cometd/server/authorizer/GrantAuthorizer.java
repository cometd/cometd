/*
 * Copyright (c) 2008-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.server.authorizer;

import java.util.EnumSet;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

/**
 * <p>This {@link Authorizer} implementation grants permission
 * for a set of operations defined at construction time.</p>
 * <p>If the operation does not match, it ignores the authorization request.</p>
 */
public class GrantAuthorizer implements Authorizer {
    /**
     * Grants {@link Operation#CREATE} authorization
     */
    public static final GrantAuthorizer GRANT_CREATE = new GrantAuthorizer(EnumSet.of(Operation.CREATE));

    /**
     * Grants {@link Operation#SUBSCRIBE} authorization
     */
    public static final GrantAuthorizer GRANT_SUBSCRIBE = new GrantAuthorizer(EnumSet.of(Operation.SUBSCRIBE));

    /**
     * Grants {@link Operation#PUBLISH} authorization
     */
    public static final GrantAuthorizer GRANT_PUBLISH = new GrantAuthorizer(EnumSet.of(Operation.PUBLISH));

    /**
     * Grants {@link Operation#CREATE} and {@link Operation#SUBSCRIBE} authorization
     */
    public static final GrantAuthorizer GRANT_CREATE_SUBSCRIBE = new GrantAuthorizer(EnumSet.of(Operation.CREATE, Operation.SUBSCRIBE));

    /**
     * Grants {@link Operation#SUBSCRIBE} and {@link Operation#PUBLISH} authorization
     */
    public static final GrantAuthorizer GRANT_SUBSCRIBE_PUBLISH = new GrantAuthorizer(EnumSet.of(Operation.SUBSCRIBE, Operation.PUBLISH));

    /**
     * Grants {@link Operation#CREATE}, {@link Operation#SUBSCRIBE} and {@link Operation#PUBLISH} authorization
     */
    public static final GrantAuthorizer GRANT_ALL = new GrantAuthorizer(EnumSet.allOf(Operation.class));

    /**
     * Grants no authorization, the authorization request is ignored
     */
    public static final GrantAuthorizer GRANT_NONE = new GrantAuthorizer(EnumSet.noneOf(Operation.class));

    private final EnumSet<Authorizer.Operation> _operations;

    private GrantAuthorizer(final EnumSet<Authorizer.Operation> operations) {
        _operations = operations;
    }

    @Override
    public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message) {
        if (_operations.contains(operation)) {
            return Result.grant();
        }
        return Result.ignore();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + _operations;
    }
}
