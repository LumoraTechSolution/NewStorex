package com.lumora.pos.user;

/**
 * What a user may do (M3-08).
 *
 * <p>Named for the action, not the screen. {@code MANAGE_PRODUCTS} survives the back office being
 * reorganised; {@code PRODUCTS_TAB} would not, and a permission that has to be renamed every time
 * the UI moves is a permission nobody keeps accurate.
 *
 * <p>These are deliberately coarse. Four roles and a handful of verbs cover a shop with one till
 * and a few staff, and every extra permission is another combination nobody will ever test. Split
 * one only when a real shop needs the two halves apart.
 */
public enum Permission {

    /** Ring up a sale. Everyone who works the till, which is everyone. */
    SELL("ring up sales"),

    /** Open a shift, count the drawer, close it and print the Z-report. */
    RUN_SHIFT("open or close a shift"),

    /** Pay-ins, pay-outs and drops (M2-05) — money moving without a sale. */
    MOVE_CASH("record a cash movement"),

    /**
     * Authorise a refund (M2-07). The gate this whole package exists for: the one action on the
     * till that hands money back, and the reason the manager PIN was invented before users were.
     */
    AUTHORISE_REFUND("authorise refunds"),

    /** Reach the back office at all (M3-01). Everything below it also needs this. */
    BACK_OFFICE("open the back office"),

    /** Create and edit products, prices and barcodes (M3-02, M3-03). */
    MANAGE_PRODUCTS("change products or prices"),

    /** Goods received, adjustments and stocktakes (M3-04 … M3-06) — anything that writes stock. */
    MANAGE_STOCK("change stock"),

    /**
     * Create users, set their PINs and change their roles.
     *
     * <p>The one permission that can grant every other one, which is why only OWNER holds it. A
     * MANAGER who could appoint managers is an owner in everything but name.
     */
    MANAGE_USERS("manage users");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    /**
     * The verb phrase used when telling somebody they may not do this, as in "Nimal is a cashier
     * and cannot authorise refunds".
     *
     * <p>Lives on the permission rather than in the message-building code so that adding a
     * permission cannot leave a refusal message reading "cannot MANAGE_STOCK". The compiler asks
     * for the phrase at the same moment it asks for the name.
     */
    public String describe() {
        return description;
    }
}
