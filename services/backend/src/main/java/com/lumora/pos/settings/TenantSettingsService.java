package com.lumora.pos.settings;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-shop policy: the cash variance threshold (D1).
 *
 * <p>This class also held the shop-wide manager PIN (M2-07) until M3-08 replaced it with real
 * users. The hash moved to {@code users.pin_hash} in V109 and the column is gone — see
 * {@code UserService}, and see the V109 header for why two credential stores for one gate is a
 * bug waiting for a rotation.
 *
 * <h2>D1, resolved</h2>
 *
 * The variance threshold is per-tenant and always was going to have to be. A jeweller counting
 * LKR 400,000 of takings and a grocer counting LKR 12,000 do not mean the same thing by "the
 * drawer is out": a fixed LKR 100 is noise to one and an obstruction to the other, and a gate that
 * fires on every shift is a gate cashiers learn to click through. The default is LKR 100.00, which
 * suits the grocer, and the row exists so the jeweller can change it without a migration.
 */
@Service
public class TenantSettingsService {

    /** LKR 100.00. The grocer's number — see the class comment on why it is only a default. */
    public static final long DEFAULT_VARIANCE_THRESHOLD_MINOR = 10_000L;

    private final JdbcTemplate jdbc;

    public TenantSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The threshold above which closing a shift needs a reason (M2-04).
     *
     * <p>Falls back to the default when a shop has no settings row rather than failing: a till that
     * cannot close a shift because nobody ran a configuration step is a till that cannot be
     * reconciled, which is worse than one reconciled against a sensible default.
     */
    @Transactional(readOnly = true)
    public long cashVarianceThresholdMinor(long tenantId) {
        List<Long> found =
                jdbc.queryForList(
                        "SELECT cash_variance_threshold_minor FROM tenant_settings WHERE tenant_id = ?",
                        Long.class,
                        tenantId);
        return found.isEmpty() ? DEFAULT_VARIANCE_THRESHOLD_MINOR : found.get(0);
    }

    /**
     * The shop's identity as the VAT registration certificate records it (M5-09).
     *
     * <p>Unlike the variance threshold above, this has <b>no default and no fallback</b>. Missing
     * settings there mean a shift closes against a sensible number; missing settings here would
     * mean a tax invoice printed with somebody's guess at a TIN, which the purchaser then files and
     * claims input credit against. Empty is returned and {@code TaxInvoiceService} refuses to
     * issue — an unconfigured shop must not be able to produce a legal document.
     */
    @Transactional(readOnly = true)
    public Optional<SupplierIdentity> supplierIdentity(long tenantId) {
        List<SupplierIdentity> found =
                jdbc.query(
                        """
                        SELECT s.supplier_tin,
                               coalesce(s.supplier_registered_name, t.name) AS registered_name,
                               s.supplier_address
                          FROM tenant_settings s
                          JOIN tenants t ON t.id = s.tenant_id
                         WHERE s.tenant_id = ?
                        """,
                        (rs, row) ->
                                new SupplierIdentity(
                                        rs.getString("supplier_tin"),
                                        rs.getString("registered_name"),
                                        rs.getString("supplier_address")),
                        tenantId);

        // A row that exists but has not been filled in is the same as no row: all three are
        // required on the face of the invoice, so two out of three is not a partial success.
        return found.stream().filter(SupplierIdentity::isComplete).findFirst();
    }

    /** Set by M5-03's first-run wizard, and by the back office until it exists. */
    @Transactional
    public void setSupplierIdentity(long tenantId, SupplierIdentity identity) {
        jdbc.update(
                """
                INSERT INTO tenant_settings (tenant_id, supplier_tin, supplier_registered_name, supplier_address)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    supplier_tin = excluded.supplier_tin,
                    supplier_registered_name = excluded.supplier_registered_name,
                    supplier_address = excluded.supplier_address
                """,
                tenantId,
                identity.tin(),
                identity.registeredName(),
                identity.address());
    }

    /**
     * Gazette 2481/22 §2.1 — the three things that must appear in the top left-hand corner of a
     * tax invoice. The telephone number §2.1(d) allows is optional and deliberately not here.
     */
    public record SupplierIdentity(String tin, String registeredName, String address) {

        public boolean isComplete() {
            return notBlank(tin) && notBlank(registeredName) && notBlank(address);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
