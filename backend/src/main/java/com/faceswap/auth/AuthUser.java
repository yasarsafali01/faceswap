package com.faceswap.auth;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

/**
 * Authenticated caller resolved from an access token. getName() returns the user id so that
 * STOMP user destinations (/user/queue/...) route by id.
 */
public record AuthUser(long id, List<String> roles, String jti, Instant tokenExpiresAt) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
