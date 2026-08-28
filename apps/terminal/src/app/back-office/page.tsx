'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';

import { OperatorPrompt } from '@/components/OperatorPrompt';
import { CustomersScreen } from '@/components/CustomersScreen';
import { ProductsScreen } from '@/components/ProductsScreen';
import { ReportsScreen } from '@/components/ReportsScreen';
import { StockScreen } from '@/components/StockScreen';
import { UsersScreen } from '@/components/UsersScreen';
import { LicenceNotice } from '@/components/LicenceNotice';
import { useBackOffice, type Permission } from '@/lib/useBackOffice';
import { useEntitlement, type Capability } from '@/lib/useEntitlement';
import { useOperator } from '@/lib/useOperator';

/**
 * The back office (M3-01) — everything the owner does on the shop PC, all of it offline.
 *
 * <h2>A route, not another overlay</h2>
 *
 * The till is a fixed appliance: one screen, no navigation, nothing scrolls but the cart. The back
 * office is the opposite kind of surface — lists, forms, several sections — and forcing it into
 * the till's shell would either wreck that shell or produce a back office nobody can use. So it is
 * a separate route, and leaving it returns to a till that never moved.
 *
 * <h2>It is the one place a mouse is allowed</h2>
 *
 * Gate M1 is twenty consecutive sales without touching a mouse, and it is about the till. This
 * screen is used a few times a week, sitting down, to type names and prices into forms — the one
 * job a pointer is genuinely better at. Sign-in is still keyboard-only, because it is the same
 * PIN entry the till uses and it should not behave differently here.
 *
 * <h2>Everything behind the sign-in is gated twice</h2>
 *
 * Once here, to hide what would be refused, and again on every endpoint. The hiding is a courtesy:
 * a screen that only looks locked is unlocked to anybody who opens the network tab.
 */
type Section = 'USERS' | 'PRODUCTS' | 'STOCK' | 'CUSTOMERS' | 'REPORTS';

/**
 * The branch this shop PC is. Hardcoded here exactly as it is on the till, and for the same
 * reason: until M5-03's first-run wizard writes it down, there is one branch and inventing a
 * settings mechanism for a single constant would be work that the wizard then replaces.
 */
const BRANCH_CODE = 'KND';

const SECTIONS: readonly {
  id: Section;
  label: string;
  /**
   * The permission needed to *look* at the section, which is not the one needed to change
   * anything in it. Users and Products are both readable by anyone who got through the front
   * door and writable only by a specific role, and each screen greys out its own buttons —
   * so gating the nav on the write permission would hide a list a manager is entitled to read.
   */
  needs: Permission;
  /**
   * The capability the shop's plan has to include (M4-09), or null for a section every plan gets.
   *
   * A different question from `needs`, and the two are deliberately not merged: `needs` is about
   * who this person is, and this is about what the business has bought. One is a permission and
   * the other is a product boundary, and a section can be refused by either.
   *
   * Users, Products, Stock and Reports carry none. Selling and knowing what you sold is the
   * product; a plan that took the catalogue away would leave a till that cannot be restocked.
   */
  requires: Capability | null;
  /** Null once the section exists. Until then, the task that will build it. */
  pending: string | null;
}[] = [
  { id: 'USERS', label: 'Users', needs: 'BACK_OFFICE', requires: null, pending: null },
  { id: 'PRODUCTS', label: 'Products', needs: 'BACK_OFFICE', requires: null, pending: null },
  { id: 'STOCK', label: 'Stock', needs: 'MANAGE_STOCK', requires: null, pending: null },
  {
    id: 'CUSTOMERS',
    label: 'Customers',
    needs: 'BACK_OFFICE',
    requires: 'customers',
    pending: null,
  },
  { id: 'REPORTS', label: 'Reports', needs: 'BACK_OFFICE', requires: null, pending: null },
];

export default function BackOfficePage() {
  const router = useRouter();
  const office = useBackOffice();
  const operator = useOperator();
  const { allows } = useEntitlement();
  const [section, setSection] = useState<Section>('USERS');
  /**
   * Set when the Reports screen sends the reader to stock on hand, so the Stock screen opens with
   * that panel already up. Cleared as soon as the section changes again — it is a one-shot
   * instruction about how to arrive, not a mode.
   */
  const [openStockOnHand, setOpenStockOnHand] = useState(false);

  const leave = useCallback(() => {
    office.signOut();
    router.push('/');
  }, [office, router]);

  // Sign-in is keyboard-driven for the same reason the till is: it is PIN entry, and PIN entry
  // that behaves differently in two places is PIN entry somebody gets wrong in one of them.
  useEffect(() => {
    if (office.session) return;
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        leave();
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        if (operator.field === 'CODE') {
          operator.advance();
        } else if (operator.ready) {
          void office.signIn(operator.code, operator.pin).then((ok) => {
            // A refusal does not say which half was wrong, so leaving the code standing would
            // imply it was the right one.
            if (!ok) operator.reset();
          });
        }
        return;
      }
      operator.onKey(event);
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [leave, office, operator]);

  if (!office.session) {
    return (
      <main className="flex h-full items-center justify-center p-8">
        <div className="border-hair bg-surface flex w-full max-w-md flex-col gap-4 rounded-lg border p-6">
          <header>
            <h1 className="text-ink text-lg font-semibold">Back office</h1>
            <p className="text-ink-3 text-sm">Sign in to manage the shop.</p>
          </header>

          <OperatorPrompt operator={operator} label="Your user code and PIN." />

          {office.error && (
            <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
              {office.error}
            </p>
          )}

          <footer className="border-hair text-ink-3 flex justify-between gap-3 border-t pt-3 text-xs">
            <span>user code, then PIN</span>
            <span>Tab switch</span>
            <span className={operator.ready ? 'text-accent font-semibold' : ''}>
              Enter {office.busy ? 'checking…' : 'sign in'}
            </span>
            <span>Esc back to the till</span>
          </footer>
        </div>
      </main>
    );
  }

  const active = SECTIONS.find((s) => s.id === section)!;

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* The back office is where an owner will actually be when they read this (M4-09). */}
      <LicenceNotice />

      <header className="border-hair flex shrink-0 flex-wrap items-center justify-between gap-4 border-b px-4 py-3">
        <div>
          <h1 className="text-ink font-semibold">Back office</h1>
          <p className="text-ink-3 text-sm">
            {office.session.displayName} · {office.session.role.toLowerCase()}
          </p>
        </div>
        <button
          type="button"
          onClick={leave}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Back to the till
        </button>
      </header>

      <div className="flex min-h-0 flex-1">
        <nav className="border-hair w-48 shrink-0 border-r p-3">
          <ul className="flex flex-col gap-1">
            {SECTIONS.map((entry) => {
              // Both gates, and either one closes it. Greyed rather than hidden in both cases: an
              // owner who cannot find Customers at all rings up to ask whether it exists, whereas
              // one who can see it greyed knows there is something to buy.
              const allowed =
                office.can(entry.needs) && (!entry.requires || allows(entry.requires));
              return (
                <li key={entry.id}>
                  <button
                    type="button"
                    disabled={!allowed}
                    onClick={() => {
                      setOpenStockOnHand(false);
                      setSection(entry.id);
                    }}
                    className={`min-h-touch w-full rounded px-3 text-left ${
                      entry.id === section ? 'text-accent font-semibold' : 'text-ink-2'
                    } ${allowed ? '' : 'opacity-40'}`}
                  >
                    {entry.label}
                  </button>
                </li>
              );
            })}
          </ul>
        </nav>

        <main className="min-h-0 flex-1 overflow-y-auto p-6">
          {active.requires && !allows(active.requires) ? (
            <div className="flex flex-col gap-2">
              <h2 className="text-ink text-lg font-semibold">{active.label}</h2>
              {/*
                Reachable without clicking a disabled button: the section is remembered in state,
                so a plan that changes while somebody is signed in leaves them standing on a
                screen they no longer have. Refused here rather than left rendering, and the
                endpoints behind it are the gate that actually holds.
              */}
              <p className="text-ink-3 text-sm">Not included in this shop&rsquo;s plan.</p>
            </div>
          ) : active.pending ? (
            <div className="flex flex-col gap-2">
              <h2 className="text-ink text-lg font-semibold">{active.label}</h2>
              {/*
                Named with the task that will build it rather than a bare "coming soon". A shop
                owner reading this learns nothing either way, but the person who opens this file
                next learns exactly where the work is tracked.
              */}
              <p className="text-ink-3 text-sm">Not built yet — {active.pending}.</p>
            </div>
          ) : section === 'PRODUCTS' ? (
            <ProductsScreen office={office} />
          ) : section === 'STOCK' ? (
            <StockScreen office={office} branchCode={BRANCH_CODE} openOnHand={openStockOnHand} />
          ) : section === 'CUSTOMERS' ? (
            <CustomersScreen office={office} />
          ) : section === 'REPORTS' ? (
            <ReportsScreen
              office={office}
              onOpenStock={() => {
                setOpenStockOnHand(true);
                setSection('STOCK');
              }}
            />
          ) : (
            <UsersScreen office={office} />
          )}
        </main>
      </div>
    </div>
  );
}
