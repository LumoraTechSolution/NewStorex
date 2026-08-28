'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

import { FIELD_CLASS, Labelled, NUMERIC_FIELD_CLASS } from './Labelled';

/**
 * Ctrl+I — issue an IRD tax invoice against a receipt (M5-09).
 *
 * <h2>Why this is not on the selling path</h2>
 *
 * A tax invoice is issued **on request**, which in a shop means occasionally and after the fact:
 * the customer has the receipt in their hand and asks. It is not part of ringing up a sale, so it
 * does not take one of the cashier's twelve function keys. Ctrl+I, like Ctrl+B for the back
 * office — deliberately outside the vocabulary somebody uses at speed with a queue waiting.
 *
 * <h2>Most of this form stays empty, and that is correct</h2>
 *
 * Gazette 2481/22 §3.1 lists the purchaser's TIN, name and address among an invoice's particulars.
 * Circular SEC/2026/E/03 §4.3 is the reading that governs: they are required *"where the purchaser
 * is VAT-registered"*. A consumer has no TIN and none of this is collected, so the fields are
 * marked optional on the face of the form rather than being something a cashier learns to skip past
 * with placeholder text. The server refuses a TIN with no name — half a purchaser block is not a
 * purchaser — and the form says so before the request rather than after.
 *
 * <h2>What it will not let you do</h2>
 *
 * Nothing here can invent a supply. The only input that matters is a receipt number, and an unknown
 * one ends the flow — the same construction M2-06 used for refunds. Issuing twice returns the
 * invoice that already exists, so a double-press reprints instead of creating a second legal
 * document for one supply.
 */
export interface IssuedTaxInvoice {
  clientUuid: string;
  invoiceNumber: string;
  issuedAt: string;
  suppliedAt: string;
  saleInvoiceNumber: string;
  supplier: { tin: string; registeredName: string; address: string };
  purchaser: { tin: string; name: string; address: string } | null;
  totalExclVatMinor: number;
  vatMinor: number;
  totalInclVatMinor: number;
  lines: {
    lineNo: number;
    name: string;
    qty: number;
    unitPriceMinor: number;
    exVatMinor: number;
    taxRateBp: number;
  }[];
}

export function TaxInvoiceOverlay({
  defaultSaleInvoiceNumber,
  onIssued,
  onClose,
}: {
  /** The sale just completed, so the commonest case is Enter with nothing typed. */
  defaultSaleInvoiceNumber: string | null;
  onIssued: (invoice: IssuedTaxInvoice) => void;
  onClose: () => void;
}) {
  const [saleInvoiceNumber, setSaleInvoiceNumber] = useState(defaultSaleInvoiceNumber ?? '');
  const [purchaserTin, setPurchaserTin] = useState('');
  const [purchaserName, setPurchaserName] = useState('');
  const [purchaserAddress, setPurchaserAddress] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const firstField = useRef<HTMLInputElement>(null);
  useEffect(() => {
    firstField.current?.focus();
  }, []);

  const wantsPurchaser = purchaserTin.trim().length > 0;

  const issue = useCallback(async () => {
    if (busy) return;
    if (saleInvoiceNumber.trim().length === 0) {
      setError('Enter the receipt number the invoice is for.');
      return;
    }
    // Checked here as well as on the server so the cashier hears it while the customer is still
    // standing there, not as a rejected request after they have walked off.
    if (wantsPurchaser && (purchaserName.trim() === '' || purchaserAddress.trim() === '')) {
      setError('A VAT-registered purchaser needs a name and an address as well as a TIN.');
      return;
    }

    setBusy(true);
    setError(null);
    try {
      const response = await fetch('http://127.0.0.1:8081/api/tax-invoices', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          saleInvoiceNumber: saleInvoiceNumber.trim(),
          purchaserTin: wantsPurchaser ? purchaserTin.trim() : null,
          purchaserName: wantsPurchaser ? purchaserName.trim() : null,
          purchaserAddress: wantsPurchaser ? purchaserAddress.trim() : null,
        }),
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);
      onIssued(body as IssuedTaxInvoice);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [
    busy,
    onIssued,
    purchaserAddress,
    purchaserName,
    purchaserTin,
    saleInvoiceNumber,
    wantsPurchaser,
  ]);

  const onKeyDown = useCallback(
    (event: React.KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        void issue();
      }
    },
    [issue, onClose],
  );

  return (
    <div
      className="bg-scrim fixed inset-0 z-40 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Issue tax invoice"
      onKeyDown={onKeyDown}
    >
      <div className="bg-surface border-hair flex w-full max-w-xl flex-col gap-4 rounded border p-6">
        <div className="flex flex-col gap-1">
          <h2 className="text-ink text-lg font-semibold">Issue tax invoice</h2>
          <p className="text-ink-3 text-sm">
            The IRD document, issued against a receipt. Exempt items are left off it, as the gazette
            requires — so its total can be lower than the receipt&apos;s.
          </p>
        </div>

        <Labelled label="Receipt number">
          <input
            ref={firstField}
            className={NUMERIC_FIELD_CLASS}
            value={saleInvoiceNumber}
            onChange={(e) => setSaleInvoiceNumber(e.target.value)}
            autoComplete="off"
          />
        </Labelled>

        <div className="border-hair flex flex-col gap-3 rounded border p-4">
          <p className="text-ink-3 text-xs uppercase tracking-wider">
            Purchaser — only if VAT-registered
          </p>
          <p className="text-ink-3 text-xs">
            Leave blank for an ordinary customer. Required only where the purchaser is registered
            for VAT, in which case all three are needed.
          </p>

          <Labelled label="Purchaser TIN" hint="Nine digits">
            <input
              className={NUMERIC_FIELD_CLASS}
              value={purchaserTin}
              onChange={(e) => setPurchaserTin(e.target.value.replace(/[^0-9]/g, ''))}
              maxLength={9}
              inputMode="numeric"
              autoComplete="off"
            />
          </Labelled>

          <Labelled label="Purchaser name">
            <input
              className={FIELD_CLASS}
              value={purchaserName}
              onChange={(e) => setPurchaserName(e.target.value)}
              disabled={!wantsPurchaser}
              autoComplete="off"
            />
          </Labelled>

          <Labelled label="Purchaser address">
            <input
              className={FIELD_CLASS}
              value={purchaserAddress}
              onChange={(e) => setPurchaserAddress(e.target.value)}
              disabled={!wantsPurchaser}
              autoComplete="off"
            />
          </Labelled>
        </div>

        {error && (
          <p className="text-danger text-sm" role="alert">
            {error}
          </p>
        )}

        <div className="flex justify-end gap-3">
          <button type="button" className="min-h-touch text-ink-2 px-4" onClick={onClose}>
            Escape — cancel
          </button>
          <button
            type="button"
            className="bg-accent text-on-accent min-h-touch rounded px-6 font-semibold"
            onClick={() => void issue()}
            disabled={busy}
          >
            {busy ? 'Working…' : 'Enter — issue & print'}
          </button>
        </div>
      </div>
    </div>
  );
}
