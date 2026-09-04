'use client';

import { useCallback, useEffect, useState } from 'react';

/**
 * Who this till belongs to (M5-03).
 *
 * <h2>What this replaces</h2>
 *
 * Five `const`s at the top of `page.tsx` — shop name, tagline, address, branch code, terminal
 * code — sitting under a comment naming this task. They were right for exactly one shop, and the
 * terminal code among them is the field that must differ between two tills in the same shop or
 * their invoice numbers collide (see V121).
 *
 * <h2>Loading is a real state, not a flash of the wrong shop</h2>
 *
 * The obvious shortcut is to default the name to 'StoreX' and replace it when the fetch lands.
 * That prints the wrong header on any receipt produced in the first few hundred milliseconds
 * after a reload, which is a rare event that produces a permanently wrong piece of paper. So
 * `identity` is null until it is known, and the caller shows nothing that depends on it until
 * then.
 */

export interface ShopIdentity {
  shopName: string;
  shopAddress: string | null;
  branchCode: string;
  branchName: string;
  terminalCode: string;
}

export type SetupState =
  | { status: 'loading' }
  /** No shop on this till yet — the wizard is the whole screen. */
  | { status: 'needs-setup' }
  | { status: 'ready'; identity: ShopIdentity }
  /** The backend could not be reached or answered badly. Distinct from `needs-setup`, because
   * showing a setup wizard to a shop that already exists is how a till acquires a second
   * identity — and the server would refuse it, confusingly, at the last step. */
  | { status: 'error'; message: string };

export function useShopIdentity() {
  const [state, setState] = useState<SetupState>({ status: 'loading' });

  const load = useCallback(async () => {
    try {
      const statusResponse = await fetch('/api/setup/status');
      if (!statusResponse.ok) {
        throw new Error(`Could not read setup status (HTTP ${statusResponse.status})`);
      }
      const { provisioned } = (await statusResponse.json()) as { provisioned: boolean };
      if (!provisioned) {
        setState({ status: 'needs-setup' });
        return;
      }

      const identityResponse = await fetch('/api/setup/identity');
      if (!identityResponse.ok) {
        throw new Error(`Could not read the shop's details (HTTP ${identityResponse.status})`);
      }
      setState({ status: 'ready', identity: (await identityResponse.json()) as ShopIdentity });
    } catch (e) {
      setState({ status: 'error', message: e instanceof Error ? e.message : String(e) });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return { state, reload: load };
}
