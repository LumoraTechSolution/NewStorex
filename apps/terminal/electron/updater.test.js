import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { packagedPublisherName, updateDecision } from './services/updater.cjs';

/**
 * The update channel's one real decision (M5-11).
 *
 * <h2>Why this file is only about refusals</h2>
 *
 * An update channel is remote code execution on every shop PC that has the product. The mechanics
 * — downloading, staging, letting NSIS swap the files after the process exits — are
 * `electron-updater`'s and are not ours to test. What is ours is the answer to "may this build
 * update itself at all", and every wrong answer to that question is a shop compromised rather than
 * a shop inconvenienced.
 *
 * So `updateDecision` is deliberately pure and sits away from anything that touches Electron,
 * which is what makes it callable here at all.
 */
describe('updateDecision', () => {
  const signed = {
    url: 'https://updates.example.lk/win',
    packaged: true,
    publisherName: 'Lumora Technologies (Pvt) Ltd',
    allowUnsigned: false,
  };

  it('allows a packaged, signed build against an https feed', () => {
    const decision = updateDecision(signed);

    expect(decision.enabled).toBe(true);
    expect(decision.reason).toContain('Lumora Technologies');
  });

  /**
   * The refusal this feature exists to make.
   *
   * Without a publisher name there is nothing to compare a downloaded installer against, so
   * "update" means "run whatever that URL served". A SmartScreen warning is not the problem here;
   * the absence of any check is.
   */
  it('refuses an unsigned build, and says why in a sentence somebody can act on', () => {
    const decision = updateDecision({ ...signed, publisherName: null });

    expect(decision.enabled).toBe(false);
    expect(decision.reason).toContain('not code-signed');
    expect(decision.reason).toContain('Sign the build');
  });

  it('allows an unsigned build only under the explicit development override', () => {
    const decision = updateDecision({ ...signed, publisherName: null, allowUnsigned: true });

    expect(decision.enabled).toBe(true);
    // Loud on purpose: this string ends up in the till's log, and somebody reading that log needs
    // to see immediately that the machine is in a state no shop should be in.
    expect(decision.reason).toContain('WITHOUT SIGNATURE VERIFICATION');
  });

  it('refuses a plaintext feed even when the build is signed', () => {
    const decision = updateDecision({ ...signed, url: 'http://updates.example.lk/win' });

    expect(decision.enabled).toBe(false);
    expect(decision.reason).toContain('must be https');
  });

  it('refuses a feed that is not a URL at all', () => {
    const decision = updateDecision({ ...signed, url: 'updates.example.lk' });

    expect(decision.enabled).toBe(false);
    expect(decision.reason).toContain('not a URL');
  });

  it('is off when no feed is configured, which is not an error', () => {
    const decision = updateDecision({ ...signed, url: null });

    expect(decision.enabled).toBe(false);
    expect(decision.reason).toContain('no LUMORA_UPDATE_URL');
  });

  /**
   * A developer running `electron .` against `next dev` must never be updating itself, whatever
   * else is set. The check is first for that reason.
   */
  it('is off in an unpackaged build even when everything else is configured', () => {
    const decision = updateDecision({ ...signed, packaged: false });

    expect(decision.enabled).toBe(false);
    expect(decision.reason).toContain('not a packaged build');
  });
});

describe('packagedPublisherName', () => {
  function withYaml(contents) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'storex-update-'));
    if (contents !== null) fs.writeFileSync(path.join(dir, 'app-update.yml'), contents);
    return dir;
  }

  it('reads the publisher electron-builder wrote', () => {
    const dir = withYaml(
      'provider: generic\nurl: https://x/win\npublisherName: Lumora Technologies\n',
    );

    expect(packagedPublisherName(dir)).toBe('Lumora Technologies');
  });

  it('reads the inline-list form electron-builder writes for an array', () => {
    const dir = withYaml('publisherName: [Lumora Technologies]\n');

    expect(packagedPublisherName(dir)).toBe('Lumora Technologies');
  });

  /** Missing file, missing key and empty value all mean the same thing, and it is the safe one. */
  it('is null when there is nothing to read', () => {
    expect(packagedPublisherName(withYaml(null))).toBeNull();
    expect(packagedPublisherName(withYaml('provider: generic\nurl: https://x/win\n'))).toBeNull();
    expect(packagedPublisherName(withYaml('publisherName:  \n'))).toBeNull();
    expect(packagedPublisherName(path.join(os.tmpdir(), 'storex-nothing-here'))).toBeNull();
  });
});
