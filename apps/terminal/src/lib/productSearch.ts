/**
 * Ranking the Find box's suggestions on the Products screen (M6-17).
 *
 * The catalogue is already in memory on that screen (see ProductsScreen's header), so this runs on
 * every keystroke with no request behind it. The order follows the till's search
 * (`ProductLookup.search`) so a product comes up in the same place in both: an exact barcode first,
 * then an exact code, then a name that starts with what was typed.
 *
 * One rank is added here that the till does not have: a word inside the name starting with the
 * text. An owner looking for "Milo 400g" types "400" as often as "milo", and a plain substring
 * match would bury it among every product whose barcode or code happens to contain 400.
 */
export interface SearchableProduct {
  name: string;
  sku: string;
  barcodes: readonly string[];
}

export const SUGGESTION_LIMIT = 8;

const RANK_BARCODE = 0;
const RANK_CODE = 1;
const RANK_NAME_PREFIX = 2;
const RANK_WORD_PREFIX = 3;
const RANK_CONTAINS = 4;

/** Splits a name into words on anything that is not a letter or digit — "Milo-400g" is two. */
const WORD_BREAK = /[^\p{L}\p{N}]+/u;

function rankOf(product: SearchableProduct, needle: string, lowered: string): number | null {
  if (product.barcodes.includes(needle)) return RANK_BARCODE;

  const sku = product.sku.toLowerCase();
  if (sku === lowered) return RANK_CODE;

  const name = product.name.toLowerCase();
  if (name.startsWith(lowered)) return RANK_NAME_PREFIX;
  if (name.split(WORD_BREAK).some((word) => word.startsWith(lowered))) return RANK_WORD_PREFIX;

  if (
    name.includes(lowered) ||
    sku.includes(lowered) ||
    product.barcodes.some((barcode) => barcode.includes(needle))
  ) {
    return RANK_CONTAINS;
  }
  return null;
}

/**
 * The best `limit` matches for `text`, best first; ties broken by name.
 *
 * Blank text returns nothing rather than the first eight products: a dropdown that opens on focus
 * covers the list the owner was about to read.
 */
export function rankProducts<T extends SearchableProduct>(
  products: readonly T[],
  text: string,
  limit: number = SUGGESTION_LIMIT,
): T[] {
  const needle = text.trim();
  if (needle === '' || limit <= 0) return [];
  const lowered = needle.toLowerCase();

  const hits: { product: T; rank: number }[] = [];
  for (const product of products) {
    const rank = rankOf(product, needle, lowered);
    if (rank !== null) hits.push({ product, rank });
  }

  hits.sort((a, b) => a.rank - b.rank || a.product.name.localeCompare(b.product.name));
  return hits.slice(0, limit).map((hit) => hit.product);
}
