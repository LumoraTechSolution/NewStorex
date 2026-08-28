package com.lumora.pos.user;

import java.util.EnumSet;
import java.util.Set;

/**
 * The four roles a shop actually has (M3-08), and what each one may do.
 *
 * <h2>Why this is an enum and not a table</h2>
 *
 * There is no {@code permissions} table to join against. What a MANAGER may do is written here, in
 * version control, reviewed and covered by tests. The alternative — configurable per-role
 * permissions — sounds more flexible and buys a specific kind of misery: one shop's MANAGER
 * silently differs from another's, and a support call asking "why was this refund refused?" has no
 * answer that does not begin with reading that shop's database.
 *
 * <p>The roles are cumulative in practice but <em>not</em> modelled as a hierarchy. Each set is
 * written out in full. A hierarchy would make every permission monotonic in seniority, and the
 * first time a shop wants a supervisor who may not reach the back office, the model would have to
 * be unwound. Spelling out four short sets costs nothing and stays honest.
 *
 * <p>Kept in step with the CHECK constraint in {@code V109__users_roles_and_pins.sql}, which lists
 * the same four names. The duplication is deliberate: a role string no code can produce should not
 * be storable either.
 */
public enum Role {

    /**
     * Works the till. Sells, runs a shift, moves cash — and cannot hand money back or reach
     * anything behind the counter.
     */
    CASHIER(EnumSet.of(Permission.SELL, Permission.RUN_SHIFT, Permission.MOVE_CASH)),

    /**
     * A cashier who may authorise refunds.
     *
     * <p>This role is the reason refunds are a permission rather than a role check. In a small shop
     * the person on the floor at 8pm is not the manager, and a returns policy that requires the
     * manager's own PIN is a policy that ends with the manager's PIN written on the till.
     */
    SUPERVISOR(
            EnumSet.of(
                    Permission.SELL,
                    Permission.RUN_SHIFT,
                    Permission.MOVE_CASH,
                    Permission.AUTHORISE_REFUND)),

    /** Runs the shop day to day: everything a supervisor may do, plus the back office. */
    MANAGER(
            EnumSet.of(
                    Permission.SELL,
                    Permission.RUN_SHIFT,
                    Permission.MOVE_CASH,
                    Permission.AUTHORISE_REFUND,
                    Permission.BACK_OFFICE,
                    Permission.MANAGE_PRODUCTS,
                    Permission.MANAGE_STOCK)),

    /** Owns the shop. The only role that may appoint other users. */
    OWNER(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    /**
     * Parses a role as stored in the database.
     *
     * <p>Throws rather than defaulting. A row whose role this build does not recognise means the
     * database is ahead of the code — a downgraded till, most likely — and guessing CASHIER would
     * silently demote somebody while guessing OWNER would silently promote them. Neither is a
     * decision this method is entitled to make.
     */
    public static Role of(String stored) {
        for (Role role : values()) {
            if (role.name().equals(stored)) {
                return role;
            }
        }
        throw new IllegalStateException(
                "Unknown role in the database: "
                        + stored
                        + ". This build does not recognise it, which usually means the database was"
                        + " written by a newer version of the software.");
    }
}
