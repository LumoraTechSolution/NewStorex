package com.lumora.pos.stock;

import com.lumora.pos.web.RejectedException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who goods come from (M3-04).
 *
 * <p>Small on purpose. A v1 shop has a dozen suppliers, a name and a phone number, and modelling an
 * address book — contacts, addresses, payment terms — would be a fortnight spent on screens nobody
 * asked for. What the supplier record has to do is exactly two things: give a goods receipt
 * somebody to point at, and let a purchase report group by it.
 *
 * <p>Deactivated, never deleted, for the third time in this schema (users in V109, products and
 * categories in V110). {@code goods_receipts.supplier_id} is a foreign key and a delivery from two
 * years ago has to keep naming somebody.
 */
@Service
public class SupplierService {

    private final JdbcTemplate jdbc;

    public SupplierService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A supplier, and how many deliveries have come from them. */
    public record SupplierRow(
            long id, UUID clientUuid, String name, String contact, boolean active, int receiptCount) {}

    @Transactional(readOnly = true)
    public List<SupplierRow> list(long tenantId) {
        return jdbc.query(
                """
                SELECT s.id, s.client_uuid, s.name, s.contact, s.active,
                       (SELECT count(*) FROM goods_receipts g WHERE g.supplier_id = s.id) AS receipt_count
                  FROM suppliers s
                 WHERE s.tenant_id = ?
                 ORDER BY s.active DESC, lower(s.name)
                """,
                ROW,
                tenantId);
    }

    @Transactional(readOnly = true)
    public SupplierRow byId(long tenantId, long supplierId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT s.id, s.client_uuid, s.name, s.contact, s.active,
                           (SELECT count(*) FROM goods_receipts g WHERE g.supplier_id = s.id)
                               AS receipt_count
                      FROM suppliers s
                     WHERE s.tenant_id = ? AND s.id = ?
                    """,
                    ROW,
                    tenantId,
                    supplierId);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("No such supplier");
        }
    }

    @Transactional
    public SupplierRow create(long tenantId, UUID clientUuid, String name, String contact) {
        String clean = requireName(name);
        refuseDuplicate(tenantId, clean, null);

        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO suppliers (client_uuid, tenant_id, name, contact)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
                        RETURNING id
                        """,
                        Long.class,
                        clientUuid,
                        tenantId,
                        clean,
                        blankToNull(contact));
        return byId(tenantId, id);
    }

    /**
     * Renames a supplier, changes their contact, or retires them.
     *
     * <p>Unlike products, {@code active} rides along on the same call. There is no credential and
     * no history-bearing edit here to keep apart — the argument that split {@code setPin} and
     * {@code setActive} off elsewhere does not apply, and a second endpoint would be ceremony.
     */
    @Transactional
    public SupplierRow update(
            long tenantId, long supplierId, String name, String contact, boolean active) {
        String clean = requireName(name);
        byId(tenantId, supplierId);
        refuseDuplicate(tenantId, clean, supplierId);

        jdbc.update(
                """
                UPDATE suppliers SET name = ?, contact = ?, active = ?
                 WHERE tenant_id = ? AND id = ?
                """,
                clean,
                blankToNull(contact),
                active,
                tenantId,
                supplierId);
        return byId(tenantId, supplierId);
    }

    /** Resolves a supplier for a goods receipt, refusing a retired one. */
    @Transactional(readOnly = true)
    public SupplierRow requireActive(long tenantId, long supplierId) {
        SupplierRow supplier = byId(tenantId, supplierId);
        if (!supplier.active()) {
            throw new RejectedException(
                    supplier.name()
                            + " is retired. Bring them back on the suppliers list before booking in"
                            + " a delivery from them.");
        }
        return supplier;
    }

    // -------------------------------------------------------------------------- guards

    private void refuseDuplicate(long tenantId, String name, Long allowedId) {
        List<Long> clash =
                jdbc.queryForList(
                        """
                        SELECT id FROM suppliers
                         WHERE tenant_id = ? AND lower(btrim(name)) = lower(btrim(?))
                        """,
                        Long.class,
                        tenantId,
                        name);
        for (Long id : clash) {
            if (allowedId == null || id.longValue() != allowedId.longValue()) {
                throw new RejectedException("There is already a supplier called " + name);
            }
        }
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new RejectedException("A supplier needs a name");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new RejectedException("A supplier name must be 128 characters or fewer");
        }
        return trimmed;
    }

    /** An empty contact box means "not recorded", which is NULL — not an empty string. */
    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static final RowMapper<SupplierRow> ROW =
            (rs, row) ->
                    new SupplierRow(
                            rs.getLong("id"),
                            rs.getObject("client_uuid", UUID.class),
                            rs.getString("name"),
                            rs.getString("contact"),
                            rs.getBoolean("active"),
                            rs.getInt("receipt_count"));
}
