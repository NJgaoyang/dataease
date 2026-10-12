package io.dataease.enhance;

/** Request-scoped identity propagated only on the authenticated request thread. */
public final class EnhanceSession {
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
    private EnhanceSession() {}

    public record Identity(long userId, long orgId, String account, boolean superAdmin) {}

    public static void set(Identity identity) { CURRENT.set(identity); }
    public static Identity current() {
        Identity value = CURRENT.get();
        if (value == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Login required");
        return value;
    }
    public static void clear() { CURRENT.remove(); }
    public static Identity peek() { return CURRENT.get(); }
}