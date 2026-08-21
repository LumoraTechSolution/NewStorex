package com.lumora.pos.invoice;

import com.lumora.pos.web.RejectedException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Issues the next document number for a terminal, locally and without a network, from that
 * terminal's own reserved block (M1-12).
 *
 * <p>Format: {@code KND-T2-001047} — branch code, terminal code, then the terminal's own sequence.
 * Each terminal counts independently, which is what lets a till keep issuing legal invoice numbers
 * with the cable unplugged and still never collide with another terminal.
 *
 * <p>A terminal's block is provisioned lazily, on first use, with the wide {@link
 * #DEFAULT_RANGE_SIZE default range} — no setup step, same as before M1-12. What changed there is
 * that the range is a real, enforced boundary rather than an unbounded counter: {@link #allocate}
 * refuses once a block is exhausted instead of climbing forever, and an already-provisioned block
 * (one a future admin flow reserved with different bounds) is never widened here — this method only
 * ever creates the <em>default</em> block, and only when none exists yet.
 *
 * <h2>Credit notes count separately (M2-06)</h2>
 *
 * Since V108 a terminal has one block per document type. A credit note may not take a number out of
 * the invoice sequence: an auditor reading invoices 1047, 1048, 1050 must be able to conclude that
 * 1049 is <em>missing</em>, not that it happened to be a refund. So the two sequences advance
 * independently and a credit note is visibly not an invoice — {@code KND-T2-CN-000004}.
 *
 * <p>Like {@link com.lumora.pos.outbox.OutboxWriter}, this deliberately has no transaction of its
 * own: the number must be allocated inside the caller's transaction, so a rolled-back sale or
 * refund does not burn one.
 */
@Component
public class InvoiceNumberAllocator {

    /** The document types a terminal counts. Matches {@code ck_invoice_counters_doc_type}. */
    public enum DocType {
        INVOICE("%s-%s-%06d"),
        CREDIT_NOTE("%s-%s-CN-%06d");

        private final String format;

        DocType(String format) {
            this.format = format;
        }
    }

    private static final long DEFAULT_RANGE_START = 1;

    /** Wide enough that a single till will not exhaust it in any realistic lifetime. */
    private static final long DEFAULT_RANGE_SIZE = 999_999;

    private final JdbcTemplate jdbc;

    public InvoiceNumberAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The invoice sequence — the overwhelmingly common case, and what M0 through M1 called. */
    public String allocate(long tenantId, long branchId, String branchCode, String terminalCode) {
        return allocate(tenantId, branchId, branchCode, terminalCode, DocType.INVOICE);
    }

    public String allocate(
            long tenantId, long branchId, String branchCode, String terminalCode, DocType docType) {
        long defaultRangeEnd = DEFAULT_RANGE_START + DEFAULT_RANGE_SIZE - 1;

        // One atomic statement: two concurrent documents cannot be handed the same number. On
        // first use the row is created with the default block and 1 is returned. On every later
        // use the WHERE clause is the block's edge — once the existing row's next_seq passes its
        // own range_end (default or otherwise), the UPDATE simply does not apply and RETURNING
        // yields nothing, which the empty-result path below turns into a clear rejection instead
        // of a number climbing past what the block was ever meant to hold.
        Long sequence;
        try {
            sequence =
                    jdbc.queryForObject(
                            """
                            INSERT INTO invoice_counters
                                (tenant_id, branch_id, terminal_code, doc_type, next_seq, range_start, range_end)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, branch_id, terminal_code, doc_type) DO UPDATE
                                SET next_seq = invoice_counters.next_seq + 1
                                WHERE invoice_counters.next_seq <= invoice_counters.range_end
                            RETURNING next_seq - 1
                            """,
                            Long.class,
                            tenantId,
                            branchId,
                            terminalCode,
                            docType.name(),
                            DEFAULT_RANGE_START + 1,
                            DEFAULT_RANGE_START,
                            defaultRangeEnd);
        } catch (EmptyResultDataAccessException exhausted) {
            throw new RejectedException(
                    "%s block exhausted for terminal %s — it needs a new reserved range before it can issue another"
                            .formatted(docType.name(), terminalCode));
        }

        return docType.format.formatted(branchCode, terminalCode, sequence);
    }
}
