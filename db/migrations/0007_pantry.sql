-- Kitchen staples a user always keeps (salt, oil, butter). Per user. Pantry items still appear
-- on the shopping list, grouped separately and without an amount to buy; that's applied when
-- the list is read, not stored in it. See docs/decisions.md.
CREATE TABLE pantry_items (
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, ingredient_id)
);
