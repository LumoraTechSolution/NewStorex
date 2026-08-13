package com.lumora.pos.invoice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Issues the next invoice number for a terminal, locally and without a network.
 *
 * <p>Format: {@code KND-T2-001047} — branch code, terminal code, then the terminal's own sequence.
 * Each terminal counts independently, which is what lets a till keep issuing legal invoice numbers
 * with the cable unplugged and still never collide with another terminal.
 *
 * <p>Like {@link com.lumora.pos.outbox.OutboxWriter}, this deliberately has no transaction of its
 * own: the number must be allocated inside the sale's transaction, so a rolled-back sale does not
 * burn one.
 */
@Component
public class InvoiceNumberAllocator {

    private final JdbcTemplate jdbc;

    public InvoiceNumberAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String allocate(long tenantId, long branchId, String branchCode, String terminalCode) {
        // One atomic statement: two concurrent sales cannot be handed the same number.
        // On first use the row is created with next_seq = 2 and 1 is returned.
        Long sequence =
                jdbc.queryForObject(
                        """
                        INSERT INTO invoice_counters (tenant_id, branch_id, terminal_code, next_seq)
                        VALUES (?, ?, ?, 2)
                        ON CONFLICT (tenant_id, branch_id, terminal_code)
                        DO UPDATE SET next_seq = invoice_counters.next_seq + 1
                        RETURNING next_seq - 1
                        """,
                        Long.class,
                        tenantId,
                        branchId,
                        terminalCode);

        return "%s-%s-%06d".formatted(branchCode, terminalCode, sequence);
    }
}
