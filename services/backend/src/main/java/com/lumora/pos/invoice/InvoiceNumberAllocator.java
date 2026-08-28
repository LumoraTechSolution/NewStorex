package com.lumora.pos.invoice;

import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
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
        CREDIT_NOTE("%s-%s-CN-%06d"),

        /**
         * The IRD tax invoice (M5-09). Its format is not a constant — it carries the year and
         * month of issue — so this one is built by {@link #taxInvoiceNumber} and the template here
         * is unused. It is a member of this enum anyway because what matters is that it counts on
         * its own row in {@code invoice_counters}: a tax invoice must never consume a receipt
         * number, for the reason V108 gives about credit notes.
         */
        TAX_INVOICE("%s-%s-%06d");

        private final String format;

        DocType(String format) {
            this.format = format;
        }
    }

    /** Gazette 2481/22 §4.1(a)(v). */
    private static final int MAX_SERIAL_LENGTH = 40;

    /** Gazette 2481/22 §4.1(a)(iii): "at least one digit but not more than fifteen". */
    private static final int MAX_QQQQ_LENGTH = 15;

    /** See {@link #taxInvoiceNumber} on why this is a hyphen and not an underscore. */
    private static final String SEPARATOR = "-";

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
        return allocate(tenantId, branchId, branchCode, terminalCode, docType, Instant.now());
    }

    /**
     * @param issuedAt used only by {@link DocType#TAX_INVOICE}, whose serial carries the year and
     *     month of issue. Passed in rather than read from a clock here so a caller that has already
     *     stamped a document's issue time cannot end up with a number naming a different month.
     */
    public String allocate(
            long tenantId,
            long branchId,
            String branchCode,
            String terminalCode,
            DocType docType,
            Instant issuedAt) {
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

        if (docType == DocType.TAX_INVOICE) {
            return taxInvoiceNumber(branchCode, terminalCode, sequence, issuedAt);
        }
        return docType.format.formatted(branchCode, terminalCode, sequence);
    }

    /**
     * The serial number Gazette 2481/22 §4.1(a) prescribes: {@code YYMMM-QQQQ-XXXXX}.
     *
     * <ul>
     *   <li>{@code YY} — last two digits of the year the invoice is <em>issued</em>. Not the year
     *       of supply: an invoice raised in January against December's delivery is a January
     *       invoice.
     *   <li>{@code MMM} — first three letters of the month, uppercase, in English.
     *   <li>{@code QQQQ} — the gazette's free identifier for "branches, sections, units". Branch
     *       and terminal code together, which is what makes this compatible with §A's per-terminal
     *       blocks: each till carries its own {@code QQQQ}, so its numeric run is independent and
     *       no till needs the network to know what another has issued.
     *   <li>{@code XXXXX} — digits only, no letters or symbols. Zero-padded, which keeps it
     *       numeric and makes a printed run sort.
     * </ul>
     *
     * <p><b>The separator.</b> The gazette writes the format with underscores and then gives a
     * worked example using hyphens — {@code 26JUL-BR03-1}. They cannot both be literal. Read as
     * notation the underscores mark field boundaries and the example shows the rendered form, so
     * the example wins here. What is unambiguous, and what this enforces, is the rule that
     * actually binds: no spaces, at most forty characters.
     */
    static String taxInvoiceNumber(
            String branchCode, String terminalCode, long sequence, Instant issuedAt) {
        ZonedDateTime issued = issuedAt.atZone(ZoneId.systemDefault());
        String yy = "%02d".formatted(issued.getYear() % 100);
        String mmm = issued.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);

        // §4.1(a)(iii) allows letters, digits or both, up to fifteen characters. Anything else a
        // branch code might contain is dropped rather than printed: a separator inside QQQQ would
        // make the number ambiguous to parse back apart.
        String qqqq = (branchCode + terminalCode).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (qqqq.isEmpty()) {
            throw new IllegalStateException(
                    "Branch and terminal codes yield no alphanumeric characters for the invoice serial");
        }
        if (qqqq.length() > MAX_QQQQ_LENGTH) {
            qqqq = qqqq.substring(0, MAX_QQQQ_LENGTH);
        }

        String number = "%s%s%s%s%s%06d".formatted(yy, mmm, SEPARATOR, qqqq, SEPARATOR, sequence);
        if (number.length() > MAX_SERIAL_LENGTH) {
            throw new IllegalStateException(
                    "Tax invoice serial exceeds the gazette's 40-character limit: " + number);
        }
        return number;
    }
}
