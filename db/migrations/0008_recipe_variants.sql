-- Per-meal recipe variants: editing a planned meal's recipe saves a private copy (a variant)
-- for that one meal-plan entry, and the entry's recipe_id then points at the copy. The copy is
-- an ordinary recipes row (with its own recipe_ingredients) whose variant_of_recipe_id names
-- the original. Variants never show as recipes of their own, and each belongs to exactly one
-- entry. They are the one kind of recipe the app hard-deletes: when their entry is removed or
-- reverted to the original, nothing else references them. See docs/decisions.md.
ALTER TABLE recipes
    ADD COLUMN variant_of_recipe_id UUID REFERENCES recipes(id) ON DELETE RESTRICT,
    ADD CHECK (variant_of_recipe_id IS DISTINCT FROM id);
CREATE INDEX recipes_variant_of_recipe_id_idx ON recipes(variant_of_recipe_id) WHERE variant_of_recipe_id IS NOT NULL;
