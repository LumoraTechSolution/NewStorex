package com.lumora.pos.setup;

import com.lumora.pos.invoice.InvoiceNumberAllocator;
import com.lumora.pos.user.Role;
import com.lumora.pos.user.UserService;
import com.lumora.pos.web.RejectedException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an empty till into a shop (M5-03).
 *
 * <h2>The gap this closes</h2>
 *
 * M5-01 and M5-02 produce an installer that works: it lays down Postgres, migrates a correct
 * schema, starts the backend and opens a window. What it cannot do is sell anything, because a
 * migrated database has no tenant, {@code LocalShop.soleTenantId()} throws on the first request,
 * and the login screen has nobody to log in. The installer produced an application that no
 * shopkeeper can use, and this class is what stands between it and a pilot shop.
 *
 * <h2>Why this is not a Flyway migration</h2>
 *
 * The obvious shortcut is to seed a tenant in V121 and be done. It is wrong for the reason
 * {@code dev-seed.sql} states in its own header: migrations run on <em>every</em> till, and a real
 * shop's name, branch code and VAT number are not facts this project gets to invent. A seeded
 * default would also be the same shop on every installation of this build — which is the shape of
 * the bug where two tills both believe they are {@code T1}.
 *
 * <h2>One transaction, or none of it</h2>
 *
 * A shop is a tenant, a branch, a settings row, an owner and an invoice block, and a half-created
 * one is worse than none: a tenant with no branch fails every sale with a confusing error, and a
 * branch with no owner cannot be signed into to fix it. So this is a single transaction and the
 * wizard either produces a working till or leaves the database exactly as it found it, ready to
 * try again.
 *
 * <h2>The guard is the table, not a flag</h2>
 *
 * {@link #isProvisioned()} asks whether a tenant exists. There is deliberately no
 * {@code setup_completed} column — see V121's header, and {@code PlatformBootstrap}, which settled
 * the same question the same way for the cloud's first admin. A flag is a second source of truth
 * that can be true while the rows it describes are missing, and then the wizard is unreachable on
 * the one till that needs it.
 */
@Service
@Profile("desktop")
public class ShopSetupService {

    /**
     * The invoice block this till starts with.
     *
     * <p>Deliberately the allocator's own default rather than a narrower reserved range. M1-12
     * left room for a provisioning step to reserve a specific block — see
     * {@link InvoiceNumberAllocator}'s header on why an already-provisioned block is never widened
     * by an ordinary sale — and a shop replacing a dead till will eventually need exactly that, so
     * that its numbers resume above whatever the cloud already holds. That is a recovery flow with
     * its own screen and its own confirmation, and guessing at it here would mean every ordinary
     * first run silently reserved a range somebody has to reason about later. A brand-new shop
     * starts at 1 because a brand-new shop has issued nothing.
     */
    private static final long FIRST_INVOICE_NUMBER = 1;

    private final JdbcTemplate jdbc;
    private final UserService users;

    public ShopSetupService(JdbcTemplate jdbc, UserService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /**
     * Whether this till already belongs to a shop.
     *
     * <p>Read by the wizard's own endpoint before it shows anything, so a provisioned till never
     * offers a screen that would refuse it.
     */
    @Transactional(readOnly = true)
    public boolean isProvisioned() {
        Integer tenants = jdbc.queryForObject("SELECT count(*) FROM tenants", Integer.class);
        return tenants != null && tenants > 0;
    }

    /**
     * Who this till belongs to — the identity the terminal prints on a receipt.
     *
     * <p>Replaces five hardcoded constants in {@code page.tsx}. Read on every load rather than
     * cached in the renderer: a shop that renames a branch should see it on the next receipt, and
     * the query is one indexed join on a loopback database.
     *
     * @throws RejectedException if the till has not been set up, which the caller has already
     *     established via {@link #isProvisioned()} — this is the race, not the normal path.
     */
    @Transactional(readOnly = true)
    public ShopIdentity identity() {
        List<ShopIdentity> found =
                jdbc.query(
                        """
                        SELECT t.name AS shop_name, s.shop_address, b.code AS branch_code,
                               b.name AS branch_name, s.terminal_code
                          FROM tenants t
                          JOIN tenant_settings s ON s.tenant_id = t.id
                          JOIN branches b ON b.tenant_id = t.id
                         ORDER BY b.id
                         LIMIT 1
                        """,
                        (rs, row) ->
                                new ShopIdentity(
                                        rs.getString("shop_name"),
                                        rs.getString("shop_address"),
                                        rs.getString("branch_code"),
                                        rs.getString("branch_name"),
                                        rs.getString("terminal_code")));
        if (found.isEmpty()) {
            throw new RejectedException("This till has not been set up yet.");
        }
        return found.get(0);
    }

    /**
     * Creates the shop, its first branch, its settings, its owner and its invoice block.
     *
     * @return what the wizard needs to show on its last screen, and nothing secret — the PIN was
     *     chosen by the person typing it and is not echoed back
     * @throws RejectedException if this till already has a shop, or any field is unusable
     */
    @Transactional
    public Provisioned provision(ShopSetupRequest request) {
        // Checked inside the transaction rather than before it. The read-only variant above is for
        // the wizard's benefit; this one is the one that matters, because two wizard submissions
        // racing each other would otherwise both pass a check made outside the write.
        if (isProvisioned()) {
            // 422 rather than 409: RejectedException already means "this will not succeed if you
            // send it again unchanged", which is exactly true here. See ApiExceptionHandler.
            throw new RejectedException(
                    "This till already belongs to a shop. Setting it up again would give it a "
                            + "second identity, and its invoice numbers would no longer be its own.");
        }

        String shopName = required(request.shopName(), "The shop's name");
        String branchName = required(request.branchName(), "The branch name");
        String branchCode = code(request.branchCode(), "branch code", 8);
        String terminalCode = code(request.terminalCode(), "terminal code", 8);
        String ownerCode = required(request.ownerCode(), "The owner's user code");
        String ownerName = required(request.ownerName(), "The owner's name");

        long tenantId =
                jdbc.queryForObject(
                        "INSERT INTO tenants (client_uuid, name) VALUES (?, ?) RETURNING id",
                        Long.class,
                        UUID.randomUUID(),
                        shopName);

        long branchId =
                jdbc.queryForObject(
                        """
                        INSERT INTO branches (client_uuid, tenant_id, code, name)
                        VALUES (?, ?, ?, ?) RETURNING id
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        tenantId,
                        branchCode,
                        branchName);

        // The settings row is created here rather than left to default-on-read, because
        // terminal_code is NOT NULL and has no sensible default — see V121 on why 'T1' would be
        // the value that is right on the first till and silently wrong on the second.
        //
        // The VAT fields are written as given, which may be null: a shop that is not VAT
        // registered has no TIN, and TaxInvoiceService already refuses to issue a tax invoice
        // when they are missing rather than printing a guess. Not being able to issue a tax
        // invoice is a correct state for such a shop, not an unfinished setup.
        jdbc.update(
                """
                INSERT INTO tenant_settings
                       (tenant_id, terminal_code, shop_address,
                        supplier_tin, supplier_registered_name, supplier_address)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                terminalCode,
                blankToNull(request.shopAddress()),
                blankToNull(request.supplierTin()),
                blankToNull(request.supplierRegisteredName()),
                blankToNull(request.supplierAddress()));

        // OWNER, and the only user this creates. Every other person in the shop is added from the
        // back office by somebody who is already signed in — which requires this one to exist, and
        // is the whole reason the wizard creates a user at all rather than leaving it to M3-08's
        // screens. OWNER rather than MANAGER because there is nobody above them to grant the
        // difference later.
        //
        // UserService owns the PIN rules and the BCrypt cost, and is called rather than reimplemented
        // here: a second place that hashes a PIN is a second place that can disagree about the cost
        // factor, and the one that disagrees quietly is the one that matters.
        users.create(tenantId, UUID.randomUUID(), ownerCode, ownerName, Role.OWNER, request.ownerPin());

        // The invoice block, reserved explicitly rather than left to the allocator's lazy default.
        //
        // The lazy path would produce an identical row on the first sale, so this is not about the
        // numbers — it is about *when* the till finds out it cannot count. A shop discovering at
        // its first sale that its block was never provisioned is a shop with a customer at the
        // counter; discovering it during setup is a shop with a wizard on screen. Both document
        // types are reserved for the same reason: a credit note must never take a number out of
        // the invoice sequence (V108), so the first refund must not be the moment its block appears.
        for (InvoiceNumberAllocator.DocType docType : InvoiceNumberAllocator.DocType.values()) {
            reserveBlock(tenantId, branchId, terminalCode, docType);
        }

        return new Provisioned(tenantId, branchId, shopName, branchCode, terminalCode, ownerCode);
    }

    /**
     * Reserves one document type's block for this terminal.
     *
     * <p>Written here rather than added to {@link InvoiceNumberAllocator} because that class is on
     * the sale path and its one job is to hand out the next number without a network. A public
     * "create a block" method on it is a method an ordinary sale could reach, and M1-12 was careful
     * that a sale can only ever create the <em>default</em> block and never widen an existing one.
     */
    private void reserveBlock(
            long tenantId, long branchId, String terminalCode, InvoiceNumberAllocator.DocType docType) {
        jdbc.update(
                """
                INSERT INTO invoice_counters
                       (tenant_id, branch_id, terminal_code, doc_type, next_seq, range_start, range_end)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, branch_id, terminal_code, doc_type) DO NOTHING
                """,
                tenantId,
                branchId,
                terminalCode,
                docType.name(),
                FIRST_INVOICE_NUMBER,
                FIRST_INVOICE_NUMBER,
                InvoiceNumberAllocator.DEFAULT_RANGE_END);
    }

    // ------------------------------------------------------------------------- validation

    /**
     * A code that ends up inside an invoice number.
     *
     * <p>Upper-cased rather than refused for being lower case, because the shopkeeper typing "knd"
     * means KND and telling them off for it teaches nothing. Whitespace is refused rather than
     * stripped: a code with a space in it is a typo, and silently repairing it produces an invoice
     * number that differs from the one they thought they were setting up.
     */
    private static String code(String value, String what, int maxLength) {
        String trimmed = required(value, "The " + what).toUpperCase(Locale.ROOT);
        if (trimmed.length() > maxLength) {
            throw new RejectedException(
                    "The %s must be %d characters or fewer — it is printed on every invoice number."
                            .formatted(what, maxLength));
        }
        if (trimmed.matches(".*\\s.*")) {
            throw new RejectedException("The %s cannot contain a space.".formatted(what));
        }
        if (!trimmed.matches("[A-Z0-9]+")) {
            throw new RejectedException(
                    "The %s can only use letters and digits — it forms part of an invoice number."
                            .formatted(what));
        }
        return trimmed;
    }

    private static String required(String value, String what) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new RejectedException("%s is required.".formatted(what));
        }
        return trimmed;
    }

    /** An empty box on a form and an absent value are the same thing; the column stores one of them. */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * What the terminal prints at the top of a receipt.
     *
     * @param shopAddress nullable — a receipt without an address is untidy, not broken
     */
    public record ShopIdentity(
            String shopName,
            String shopAddress,
            String branchCode,
            String branchName,
            String terminalCode) {}

    /** What the wizard shows on its last screen. Deliberately carries nothing secret. */
    public record Provisioned(
            long tenantId,
            long branchId,
            String shopName,
            String branchCode,
            String terminalCode,
            String ownerCode) {}
}
