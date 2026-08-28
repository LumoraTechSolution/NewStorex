package com.lumora.pos.product;

import java.util.UUID;

/**
 * A category, and how many products currently sit in it (M3-02).
 *
 * <p>The count travels with the row because the one question asked of this list is "can I retire
 * this one?", and the answer is entirely the count. Fetching it separately would mean the screen
 * either renders the list twice or shows a picker that cannot answer the only question it is asked.
 */
public record CategoryRow(long id, UUID clientUuid, String name, boolean active, int productCount) {}
