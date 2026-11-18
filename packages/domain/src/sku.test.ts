import { describe, expect, it } from 'vitest';

import { FALLBACK_SKU_PREFIX, skuPrefix, suggestedSkuExample } from './sku';

/**
 * Product code prefixes (M3-02).
 *
 * The table below is a contract, not a convenience: `ProductSkuAllocatorTest` on the backend
 * asserts the same rows against the Java twin. If a case is added here it belongs there too —
 * the two implementations exist so the form can show a placeholder without a round-trip, and
 * they are only worth having while they agree.
 */
const CASES: ReadonlyArray<[name: string | null, prefix: string, why: string]> = [
  ['Beverages', 'BEV', 'the ordinary case'],
  ['Beverage', 'BEV', 'collides with Beverages, deliberately'],
  ['Tea', 'TEA', 'exactly three characters'],
  ['Tea Bags', 'TEA', 'the space is dropped before the cut, so this collides with Tea'],
  ['Rice & Grains', 'RIC', 'punctuation and spaces go'],
  ['  Dairy  ', 'DAI', 'surrounding whitespace is not a character'],
  ['3M Products', '3MP', 'digits are as good as letters'],
  ['AB', 'AB', 'shorter than three is not padded'],
  ['බීම', 'P', 'Sinhala strips to nothing'],
  ['பானங்கள்', 'P', 'Tamil strips to nothing'],
  ['日用品', 'P', 'any non-Latin script strips to nothing'],
  ['!!!', 'P', 'punctuation alone strips to nothing'],
  [null, 'P', 'no category at all'],
];

describe('skuPrefix — the stem of a category’s product codes', () => {
  it.each(CASES)('%s gives %s — %s', (name, prefix) => {
    expect(skuPrefix(name)).toBe(prefix);
  });

  it('treats undefined the way it treats null, because a form field can be either', () => {
    expect(skuPrefix(undefined)).toBe(FALLBACK_SKU_PREFIX);
  });

  it('reads an empty or blank name as no category rather than an empty prefix', () => {
    expect(skuPrefix('')).toBe(FALLBACK_SKU_PREFIX);
    expect(skuPrefix('   ')).toBe(FALLBACK_SKU_PREFIX);
  });

  /**
   * The one that would only ever break on somebody else's machine: under a Turkish locale
   * 'i'.toLocaleUpperCase() is 'İ', which strips out and would turn ICE into CE.
   */
  it('uppercases the same way regardless of the machine’s language', () => {
    expect(skuPrefix('iced tea')).toBe('ICE');
    expect(skuPrefix('istanbul imports')).toBe('IST');
  });

  it('never returns more than three characters', () => {
    for (const [name] of CASES) {
      expect(skuPrefix(name).length).toBeLessThanOrEqual(3);
    }
  });
});

describe('suggestedSkuExample — what the placeholder shows', () => {
  it('pads a category’s number to three', () => {
    expect(suggestedSkuExample('Beverages')).toBe('BEV-001');
  });

  it('pads the uncategorised number to four, because everything shares that counter', () => {
    expect(suggestedSkuExample(null)).toBe('P-0001');
  });
});
