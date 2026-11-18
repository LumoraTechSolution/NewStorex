import { describe, expect, it } from 'vitest';

import { rankProducts, SUGGESTION_LIMIT, type SearchableProduct } from './productSearch';

function product(name: string, sku = 'X-0000', barcodes: string[] = []): SearchableProduct {
  return { name, sku, barcodes };
}

const names = (products: SearchableProduct[]) => products.map((p) => p.name);

describe('rankProducts', () => {
  it('returns nothing for blank text', () => {
    expect(rankProducts([product('Milo 400g')], '')).toEqual([]);
    expect(rankProducts([product('Milo 400g')], '   ')).toEqual([]);
  });

  it('puts an exact barcode first, even over a name that starts with the text', () => {
    const products = [product('4791 Biscuits'), product('Milo 400g', 'BEV-0001', ['4791'])];
    expect(names(rankProducts(products, '4791'))).toEqual(['Milo 400g', '4791 Biscuits']);
  });

  it('puts an exact code above a name prefix', () => {
    const products = [product('Bev Cola'), product('Milo 400g', 'BEV')];
    expect(names(rankProducts(products, 'bev'))).toEqual(['Milo 400g', 'Bev Cola']);
  });

  it('ranks name prefix, then word prefix, then contains', () => {
    const products = [
      product('Chocolate Milk'), // second word starts with it
      product('Buttermilk'), // only contains it
      product('Milk Powder 400g'), // name prefix
    ];
    expect(names(rankProducts(products, 'milk'))).toEqual([
      'Milk Powder 400g',
      'Chocolate Milk',
      'Buttermilk',
    ]);
  });

  it('matches a word that starts after punctuation', () => {
    expect(names(rankProducts([product('Milo-400g')], '400'))).toEqual(['Milo-400g']);
  });

  it('is case-insensitive on name and code', () => {
    expect(names(rankProducts([product('Milo 400g', 'bev-0001')], 'MILO'))).toEqual(['Milo 400g']);
    expect(names(rankProducts([product('Milo 400g', 'bev-0001')], 'BEV-0001'))).toEqual([
      'Milo 400g',
    ]);
  });

  it('matches part of a barcode, below a name match', () => {
    const products = [product('Tea', 'T-1', ['4790001234']), product('Cola 1234')];
    expect(names(rankProducts(products, '1234'))).toEqual(['Cola 1234', 'Tea']);
  });

  it('breaks ties by name', () => {
    const products = [product('Milo 1kg'), product('Milo 400g'), product('Milo RTD')];
    expect(names(rankProducts(products, 'milo'))).toEqual(['Milo 1kg', 'Milo 400g', 'Milo RTD']);
  });

  it(`stops at ${SUGGESTION_LIMIT} by default`, () => {
    const many = Array.from({ length: 20 }, (_, i) => product(`Tea ${String(i).padStart(2, '0')}`));
    expect(rankProducts(many, 'tea')).toHaveLength(SUGGESTION_LIMIT);
  });

  it('leaves out what does not match', () => {
    expect(rankProducts([product('Milo 400g')], 'tea')).toEqual([]);
  });
});
