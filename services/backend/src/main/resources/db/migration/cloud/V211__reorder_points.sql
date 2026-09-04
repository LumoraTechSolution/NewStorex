-- V211 — the cloud's half of the reorder point (M3-15).
--
-- The desktop tier grew `products.reorder_point` in V120. The product payload is deliberately the
-- **whole row every time** rather than a diff (see `ProductAdminService.enqueue`), so the moment
-- the shop starts sending this field the cloud either stores it or silently drops it. Dropping it
-- would leave the console permanently unable to answer "what is this shop about to run out of" —
-- the question an owner away from the shop most wants answered, and the reason M4-11 exists.
--
-- Same semantics as the desktop column, and they have to stay the same: NULL is "not watched",
-- which is a different fact from 0, which is a real threshold meaning "tell me when it is empty".
-- If the two tiers disagreed about that, the console's low-stock list and the till's would differ
-- by exactly the products a shopkeeper cared most about.
--
-- Still no stored level here either. The cloud does not compute on hand at all yet; when it does
-- (M4-11) it will sum the movements it was sent, exactly as the shop does.

ALTER TABLE products
    ADD COLUMN reorder_point integer;

COMMENT ON COLUMN products.reorder_point IS
    'Mirror of the shop''s products.reorder_point (V120). NULL means not watched; 0 is a real threshold meaning "tell me when it is empty". Never a level.';
