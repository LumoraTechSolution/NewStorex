-- V120 — the shelf that is about to be empty (M3-15).
--
-- ## The one report that changes what a shopkeeper does today
--
-- Everything else in §C's reporting answers a question about the past: what sold, what the drawer
-- came to, what the count found. This one is the only report that is about tomorrow — *you are
-- going to run out of this*. A shop that runs out of its fastest line loses the sale and sometimes
-- the customer, and unlike a variance there is a window in which the problem is still preventable.
--
-- ## A threshold per product, because "low" is not a number
--
-- The tempting shortcut is a single shop-wide figure — flag anything under five. It is wrong the
-- moment the catalogue has two kinds of thing in it, which is immediately. Five loaves is a crisis
-- and five televisions is a warehouse. The threshold has to live next to the product because it is
-- a fact about the product, not about the shop.
--
-- ## NULL means "not watched", and that is the default on purpose
--
-- A nullable column with no default rather than `DEFAULT 0`, and the difference matters. Zero is a
-- real threshold that a shopkeeper might genuinely set — *tell me the moment this hits empty* — so
-- if zero also meant "unset", the one product they cared most about would be indistinguishable
-- from the four hundred they never configured.
--
-- Defaulting every existing product to unwatched is also the only honest migration. This column
-- arrives after the catalogue exists, and inventing a threshold for a product nobody has thought
-- about would produce a first low-stock screen full of alerts the shopkeeper did not ask for and
-- cannot trust — which is how an alert screen dies (see V121's note on acknowledgement, and the
-- console's attention feed, for the same failure in a different place).
--
-- ## Still no stored level
--
-- This is a threshold, not a balance, and §A is untouched. On hand remains `Σ stock_movements` via
-- the `stock_on_hand` view (V114). "Low" is a comparison evaluated when asked — `qty_on_hand <
-- reorder_point` — and nothing writes, caches or maintains a low-stock flag anywhere. A boolean
-- column saying `is_low` would be a derived fact stored twice, and the second copy would be wrong
-- every time a sale moved the first.

ALTER TABLE products
    ADD COLUMN reorder_point integer CHECK (reorder_point >= 0);

COMMENT ON COLUMN products.reorder_point IS
    'Reorder when on hand falls to or below this. NULL means not watched, which is different from 0 - zero is a real threshold meaning "tell me when it is empty". Never a stored level: low is qty_on_hand <= reorder_point, evaluated against the stock_on_hand view.';

-- Partial, because the low-stock query only ever looks at watched products and in a real catalogue
-- most rows are not watched. Indexing the NULLs would make the index larger than the question.
CREATE INDEX ix_products_reorder_point ON products (tenant_id, reorder_point)
    WHERE reorder_point IS NOT NULL;
