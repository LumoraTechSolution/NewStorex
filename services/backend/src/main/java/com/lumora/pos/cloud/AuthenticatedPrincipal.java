package com.lumora.pos.cloud;

/**
 * Who is making a cloud request, and with what kind of credential (M4-05, extended by M4-08).
 *
 * <h2>Why the kind is carried, and not just the tenant</h2>
 *
 * Both tenant-scoped credentials resolve to a tenant, so it is tempting to reduce them to one. They
 * are not interchangeable, and collapsing them would mean a token soldered into a till could read
 * the owner's whole business, and an owner's phone session could push sales into the shop's ledger.
 * Neither is a thing anybody wants and both would be invisible — the request would simply succeed.
 *
 * <p>So an endpoint states the kind it serves, and {@code TenantAuthFilter} records which one
 * arrived. A till token on a reporting endpoint is a 403, not a 200.
 *
 * <h2>The third kind has no tenant at all</h2>
 *
 * M4-08's {@link Kind#PLATFORM} is Lumora staff, and the whole point of it is that it spans shops:
 * it lists every tenant, licences them, and creates the owner logins the other two kinds depend on.
 * There is no single tenant it is confined to, and inventing one — zero, or the first row, or the
 * tenant named in a parameter — is how a staff session quietly starts writing into somebody's shop.
 *
 * <p>So the tenant is genuinely absent for that kind, and {@link #tenantId()} <b>throws</b> rather
 * than returning a number. Every existing caller reaches it through {@link CloudPrincipals#require},
 * which asserts the kind first, so the throw is unreachable from any correct call site — it exists
 * to make an incorrect one fail loudly at the top of the request instead of succeeding against the
 * wrong shop. A platform endpoint that needs a tenant takes it from the path and checks it exists;
 * that is an explicit act, which is what it should look like.
 *
 * @param tenantIdOrNull prefer {@link #tenantId()}. Null for {@link Kind#PLATFORM} and never for
 *     the other two. Named so that reading it directly looks like the deliberate act it is.
 */
public record AuthenticatedPrincipal(
        Kind kind, Long tenantIdOrNull, long credentialId, String label) {

    public enum Kind {
        /** V205's machine token, baked into a terminal at activation. Writes; never reads. */
        TILL,
        /**
         * V208's console session, belonging to a shop's owner.
         *
         * <p>Reads, and writes <b>exactly one thing</b>: an acknowledgement that somebody looked at
         * a cash variance (M6-10, {@code V214}). That widening was deliberate and is deliberately
         * narrow — it touches no money and no ledger, it cannot change what a shift says, it is
         * attributed to the session that did it, and the shift stays listed afterwards under
         * {@code ?reviewed=true}. A stolen session can hide an alert; it still cannot alter a figure
         * or hide a shift.
         *
         * <p>The alternative was making an owner walk to the till to clear a variance they are
         * looking at on their phone, which is the whole reason the console exists.
         */
        CONSOLE,
        /**
         * V209's platform session, belonging to Lumora staff. Spans tenants, and is the only kind
         * that can create one. Never touches a shop's ledger — sales, stock, shifts and refunds are
         * all behind {@link #TILL}.
         */
        PLATFORM
    }

    /** A till or console credential, already confined to one shop. */
    public static AuthenticatedPrincipal ofTenant(
            Kind kind, long tenantId, long credentialId, String label) {
        if (kind == Kind.PLATFORM) {
            throw new IllegalArgumentException("A platform principal is not confined to a tenant");
        }
        return new AuthenticatedPrincipal(kind, tenantId, credentialId, label);
    }

    /** A staff credential, which spans every shop and belongs to none. */
    public static AuthenticatedPrincipal ofPlatform(long sessionId, String label) {
        return new AuthenticatedPrincipal(Kind.PLATFORM, null, sessionId, label);
    }

    /**
     * The only tenant this request may touch.
     *
     * @throws IllegalStateException for a {@link Kind#PLATFORM} principal, which has no such
     *     tenant. Loud rather than defaulting: the quiet version of this bug reads or writes the
     *     wrong shop and returns 200.
     */
    public long tenantId() {
        if (tenantIdOrNull == null) {
            throw new IllegalStateException(
                    "A "
                            + kind
                            + " principal is not scoped to a tenant — take the tenant from the "
                            + "request and check it, or require a tenant-scoped credential kind.");
        }
        return tenantIdOrNull;
    }

    public boolean is(Kind expected) {
        return kind == expected;
    }
}
