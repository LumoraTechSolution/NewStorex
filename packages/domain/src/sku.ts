/**
 * Deriving a product code's prefix from a category name (M3-02).
 *
 * The back office lets the product code be left blank, and the backend then makes one up from the
 * product's category: `BEV-001` for the first beverage, `P-0001` for something not in a category
 * yet. The form shows that shape as a placeholder while the owner is still typing, which is what
 * this module is for.
 *
 * **This is a deliberate twin of `ProductSkuAllocator.prefixFor` in the backend**, and the two are
 * pinned by the same table of cases in their tests. That is not the rule the package header states
 * about implementing things twice — that rule is about money, where a drift is a rupee nobody
 * catches. A drift here shows a wrong placeholder and is visible the moment the product saves.
 *
 * The number is deliberately *not* derived here. Only the backend can know it, and even it can
 * only know it at the moment of the insert — another till may take the next one first. So the form
 * shows the prefix and leaves the box genuinely empty, rather than pre-filling a number that might
 * not survive the round-trip.
 */

/**
 * Uncategorised, and what a category name with no Latin characters falls back to.
 *
 * Not `GEN`: V110 keeps a product's category nullable precisely so a shop is not pushed into
 * inventing one bucket called "General", and a code namespace full of `GEN-001` is that bucket
 * again, somewhere harder to undo.
 */
export const FALLBACK_SKU_PREFIX = 'P';

const PREFIX_LENGTH = 3;

/**
 * The stem of a category's product codes — `'Rice & Grains'` gives `'RIC'`.
 *
 * Everything outside `A-Z0-9` is dropped, so spaces and punctuation go, and a Sinhala or Tamil
 * name strips to nothing and falls back to {@link FALLBACK_SKU_PREFIX}. Names shorter than three
 * characters are not padded: `AB` is honest where `AB0` invents a character.
 *
 * Two categories may share a prefix — "Beverages" and "Beverage" both give `BEV` — and that is
 * intended. They share a counter, so the codes stay unique; only the hint is lost.
 */
export function skuPrefix(categoryName: string | null | undefined): string {
  if (categoryName === null || categoryName === undefined) return FALLBACK_SKU_PREFIX;

  // toUpperCase, not toLocaleUpperCase: under a Turkish locale the latter turns 'i' into 'İ', and
  // a till's product codes would depend on the language the PC was set up in.
  const stripped = categoryName.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (stripped === '') return FALLBACK_SKU_PREFIX;

  return stripped.slice(0, PREFIX_LENGTH);
}

/**
 * What a generated code will look like — for a placeholder, never to send.
 *
 * The real number comes from the backend's counter. This exists so the box can show `BEV-001`
 * greyed out while the owner decides whether to type their own code instead.
 */
export function suggestedSkuExample(categoryName: string | null | undefined): string {
  const prefix = skuPrefix(categoryName);
  return prefix === FALLBACK_SKU_PREFIX ? `${prefix}-0001` : `${prefix}-001`;
}
