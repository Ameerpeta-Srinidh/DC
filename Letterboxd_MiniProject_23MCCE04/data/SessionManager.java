package data;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionManager — real, server-verified login sessions, replacing the
 * "client just resends a bare username" non-auth from the earlier version.
 *
 * WHY THIS LIVES IN DatabaseServer, NOT IN AN APP SERVER:
 *   App servers are deliberately stateless (see LetterboxdAppServer's class
 *   comment) so you can run many of them behind a load balancer, and the
 *   LoadBalancer round-robins each new TCP CONNECTION to a possibly
 *   different app server — there is no guarantee two requests from the
 *   same logged-in user land on the same app server instance. If sessions
 *   were held in an app server's memory, logging in through app-server-A
 *   and then (because of round-robin) sending your next request to
 *   app-server-B would make B unable to recognize your token at all.
 *   The ONE place that already exists everywhere every request eventually
 *   goes is DatabaseServer, so that's where session state lives — the
 *   same reason real distributed systems centralize session state in a
 *   shared store (Redis, a sessions table, etc.) instead of in-process
 *   memory on a horizontally-scaled web tier.
 *
 * ConcurrentHashMap is used (not a plain HashMap + synchronized) because
 * DatabaseServer services many app-server connections concurrently via its
 * own thread pool — ConcurrentHashMap allows safe concurrent get/put
 * without serializing every session check behind one lock, the same
 * "don't over-synchronize reads" lesson as ReplicaDatabase's connection
 * pool.
 */
public class SessionManager {

    private final Map<String, String> tokenToUsername = new ConcurrentHashMap<>();

    /** Called on successful LOGIN — mints a fresh, unguessable opaque token. */
    public String createSession(String username) {
        String token = UUID.randomUUID().toString();
        tokenToUsername.put(token, username);
        return token;
    }

    /** True only if this exact token is currently valid AND belongs to claimedUsername —
     *  i.e. the client isn't just handing us a stolen/mismatched token for someone else. */
    public boolean isValidFor(String token, String claimedUsername) {
        if (token == null || claimedUsername == null) return false;
        String actual = tokenToUsername.get(token);
        return actual != null && actual.equals(claimedUsername);
    }

    public void invalidate(String token) {
        if (token != null) tokenToUsername.remove(token);
    }

    public int activeSessionCount() {
        return tokenToUsername.size();
    }
}
