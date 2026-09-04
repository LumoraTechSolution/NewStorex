'use client';

/**
 * Whose till this is, and who is standing at it.
 *
 * <h2>Written because of a misfiled sale</h2>
 *
 * A till was activated for one shop while a stale machine-level `LUMORA_CLOUD_TOKEN` from another
 * shop was still set on the PC. The environment variable won, and an afternoon's sales were
 * ingested under the wrong tenant.
 *
 * Every layer behaved correctly. The token authenticated, the cloud derived the tenant from it and
 * never from the request body, and each sale was filed exactly where the credential said it
 * belonged. **That is precisely why nothing could report it.** The till knew its own name; the
 * cloud knew the token's; and the two facts were never on the same screen. So they are now.
 *
 * <h2>It shows, it does not block</h2>
 *
 * A mismatch is drawn as a warning and nothing else happens. Refusing to sell over two differing
 * strings would put the network back on the critical path of a sale, which §A forbids outright —
 * and would do it for a condition whose commonest innocent cause is a shop renamed in the console
 * an hour ago. The cashier keeps trading; a person decides what the difference means.
 *
 * <h2>The operator is the shift's, not a session's</h2>
 *
 * There is no persistent login on the sell screen — the shift is the session (M2-01), and every
 * sale is attributed to whoever opened it. So this shows `shifts.opened_by`, which is the name the
 * receipts will carry, rather than anything about who last touched the keyboard.
 */
export function TillIdentity({
  shopName,
  terminalCode,
  operatorName,
  cloudShopName,
}: {
  shopName: string;
  terminalCode: string;
  /** Who opened the current shift, or null when the till is closed. */
  operatorName: string | null;
  /** What the cloud calls this shop, or null before a first sync. */
  cloudShopName: string | null;
}) {
  // Compared case- and space-insensitively. A shop stored as "Lahiru Retails " in one place and
  // "lahiru retails" in the other is the same shop, and a warning that fires on whitespace is a
  // warning people learn to ignore — which would cost exactly the alarm this exists to raise.
  const normalise = (value: string) => value.trim().toLowerCase();
  const mismatched = cloudShopName !== null && normalise(cloudShopName) !== normalise(shopName);

  return (
    <div className="flex min-w-0 items-center gap-2 text-xs">
      <span className="text-ink-2 truncate font-medium" title={shopName}>
        {shopName}
      </span>
      <span className="text-ink-3">·</span>
      <span className="text-ink-3">Till {terminalCode}</span>

      {operatorName ? (
        <>
          <span className="text-ink-3">·</span>
          {/*
            The name, never the user code. The code is a credential somebody types, and leaving it
            on a screen that faces a shop floor all day is how it stops being one.
          */}
          <span className="text-ink-2 truncate" title={`Signed in: ${operatorName}`}>
            {operatorName}
          </span>
        </>
      ) : (
        <>
          <span className="text-ink-3">·</span>
          {/* "No shift open" rather than blank: an empty space reads as a bug, and this is a
              real state the cashier can act on with F10. */}
          <span className="text-ink-3">No shift open</span>
        </>
      )}

      {mismatched ? (
        // A word as well as a colour, like every other status on this screen (§A): colour alone
        // carries no meaning here, and this is the one message that must survive being glanced at.
        <span
          role="alert"
          className="text-danger border-danger ml-1 shrink-0 rounded border px-1.5 py-0.5 font-medium"
          title={`This till is set up as "${shopName}" but its cloud token belongs to "${cloudShopName}". Sales are syncing to ${cloudShopName}.`}
        >
          ⚠ Cloud says {cloudShopName}
        </span>
      ) : null}
    </div>
  );
}
