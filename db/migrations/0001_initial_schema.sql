-- Initial schema. UUID primary keys via gen_random_uuid() and TIMESTAMPTZ timestamps,
-- matching shelf's established convention -- except sessions.id, which (like shelf's) has no
-- default: the session token is generated explicitly in Kotlin with a CSPRNG (Phase 3), not
-- left to the database to fill in.

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_user_id_idx ON sessions(user_id);

-- Global, instance-wide vocabulary -- NOT owner_id-scoped. See PROJECT_BRIEF.md section 4:
-- a self-hosted larder instance is fundamentally a single household, and shared ingredient/
-- unit curation compounds in value across whoever uses that instance.

CREATE TABLE ingredients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    plural_name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Case-insensitive: prevents the ingredient-line parser's auto-create-on-miss lookup from
-- racing into duplicate canonical rows for the same name written with different casing.
CREATE UNIQUE INDEX ingredients_name_lower_idx ON ingredients (lower(trim(name)));

CREATE TABLE ingredient_aliases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ingredient_id UUID NOT NULL REFERENCES ingredients(id) ON DELETE CASCADE,
    alias TEXT NOT NULL
);
-- Globally unique, not per-ingredient: one alias string must resolve to exactly one canonical
-- ingredient, or the parser's lookup becomes ambiguous.
CREATE UNIQUE INDEX ingredient_aliases_alias_lower_idx ON ingredient_aliases (lower(trim(alias)));
CREATE INDEX ingredient_aliases_ingredient_id_idx ON ingredient_aliases(ingredient_id);

CREATE TABLE units (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    abbreviation TEXT,
    dimension TEXT NOT NULL CHECK (dimension IN ('volume', 'mass', 'count')),
    -- How many of this dimension's shared base unit (milliliters for volume, grams for mass)
    -- one of this unit equals. NULL for count-dimension units, which have no universal factor
    -- -- "1 clove of garlic" only converts to a weight via an ingredient-scoped
    -- unit_conversions row below, never a fixed factor of its own.
    to_base_factor NUMERIC,
    aliases TEXT[] NOT NULL DEFAULT '{}',
    CHECK ((dimension = 'count') = (to_base_factor IS NULL))
);

CREATE TABLE unit_conversions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_unit_id UUID NOT NULL REFERENCES units(id) ON DELETE CASCADE,
    to_unit_id UUID NOT NULL REFERENCES units(id) ON DELETE CASCADE,
    factor NUMERIC NOT NULL,
    -- NULL = a global exception (rare); otherwise scoped to one ingredient, e.g. "1 clove
    -- garlic = 3g" is a fact about garlic, not about the word "clove". Reassigned, not
    -- cascade-deleted, when an ingredient is folded into another via merge-into (Phase 5).
    ingredient_id UUID REFERENCES ingredients(id) ON DELETE CASCADE,
    CHECK (from_unit_id != to_unit_id)
);
-- One row per (from, to) pair per scope -- lookup checks both directions and inverts the
-- factor going backward, rather than storing the reverse row too (see docs/decisions.md).
-- Two partial indexes, not one plain unique constraint, because Postgres treats every NULL as
-- distinct: a plain UNIQUE(from_unit_id, to_unit_id, ingredient_id) would let unlimited
-- duplicate *global* (ingredient_id IS NULL) rows through for the same pair.
CREATE UNIQUE INDEX unit_conversions_scoped_pair_idx
    ON unit_conversions (from_unit_id, to_unit_id, ingredient_id) WHERE ingredient_id IS NOT NULL;
CREATE UNIQUE INDEX unit_conversions_global_pair_idx
    ON unit_conversions (from_unit_id, to_unit_id) WHERE ingredient_id IS NULL;

CREATE TABLE recipes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    source_url TEXT,
    servings NUMERIC,
    servings_text TEXT,
    prep_time_minutes INTEGER,
    cook_time_minutes INTEGER,
    total_time_minutes INTEGER,
    tags TEXT[] NOT NULL DEFAULT '{}',
    instructions TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Soft delete -- see PROJECT_BRIEF.md section 4. NULL = active. Listing queries filter
    -- WHERE deleted_at IS NULL; a direct fetch by id does not, so a historical
    -- meal_plan_entries row can still resolve the recipe it references.
    deleted_at TIMESTAMPTZ
);
CREATE INDEX recipes_owner_id_idx ON recipes(owner_id);

CREATE TABLE recipe_ingredients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id UUID NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    raw_text TEXT NOT NULL,
    notes TEXT,
    -- Exact fraction, not a decimal/float -- see PROJECT_BRIEF.md section 4 (both Mealie and
    -- Tandoor, researched for this decision, have real precision issues from not doing this).
    quantity_numerator INTEGER,
    quantity_denominator INTEGER,
    -- NULL unit_id = a bare count of the ingredient itself (e.g. "3 eggs"), not "no unit
    -- resolved" -- see PROJECT_BRIEF.md section 4.
    unit_id UUID REFERENCES units(id),
    -- NULL ingredient_id = the parser couldn't resolve a canonical ingredient for this line;
    -- raw_text is still always present and always what displays by default.
    ingredient_id UUID REFERENCES ingredients(id),
    CHECK ((quantity_numerator IS NULL) = (quantity_denominator IS NULL)),
    CHECK (quantity_denominator IS NULL OR quantity_denominator > 0)
);
CREATE INDEX recipe_ingredients_recipe_id_idx ON recipe_ingredients(recipe_id);
CREATE INDEX recipe_ingredients_ingredient_id_idx ON recipe_ingredients(ingredient_id);

CREATE TABLE meal_plan_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_date DATE NOT NULL,
    meal_slot TEXT NOT NULL,
    -- RESTRICT, not CASCADE: recipes are soft-deleted (see recipes.deleted_at above), so the
    -- app itself never issues a real DELETE against recipes. This is a defensive backstop, not
    -- the primary protection -- it exists so a hard delete, if one ever happened outside the
    -- app, fails loudly instead of silently erasing meal-plan history.
    recipe_id UUID NOT NULL REFERENCES recipes(id) ON DELETE RESTRICT,
    servings_multiplier NUMERIC NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX meal_plan_entries_owner_id_idx ON meal_plan_entries(owner_id);
CREATE INDEX meal_plan_entries_recipe_id_idx ON meal_plan_entries(recipe_id);

CREATE TABLE shopping_lists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shopping_lists_owner_id_idx ON shopping_lists(owner_id);

CREATE TABLE shopping_list_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shopping_list_id UUID NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE,
    ingredient_id UUID REFERENCES ingredients(id),
    raw_text TEXT NOT NULL,
    quantity_numerator INTEGER,
    quantity_denominator INTEGER,
    unit_id UUID REFERENCES units(id),
    checked BOOLEAN NOT NULL DEFAULT false,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CHECK ((quantity_numerator IS NULL) = (quantity_denominator IS NULL)),
    CHECK (quantity_denominator IS NULL OR quantity_denominator > 0)
);
CREATE INDEX shopping_list_items_shopping_list_id_idx ON shopping_list_items(shopping_list_id);

-- One row per recipe that contributed to a shopping_list_item -- see PROJECT_BRIEF.md section
-- 4. Snapshotted (recipe_title, raw_text, quantity, unit as that recipe actually called for
-- it, scaled but not unit-converted), not a live join: recipe_id and meal_plan_entry_id are
-- soft references (ON DELETE SET NULL) so a generated list keeps displaying correctly even
-- after its source recipe or meal-plan entry is later deleted -- the snapshot fields are the
-- durable source of truth for display, the FKs are just an optional "jump to source" link.
CREATE TABLE shopping_list_item_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shopping_list_item_id UUID NOT NULL REFERENCES shopping_list_items(id) ON DELETE CASCADE,
    recipe_id UUID REFERENCES recipes(id) ON DELETE SET NULL,
    recipe_title TEXT NOT NULL,
    meal_plan_entry_id UUID REFERENCES meal_plan_entries(id) ON DELETE SET NULL,
    raw_text TEXT NOT NULL,
    quantity_numerator INTEGER,
    quantity_denominator INTEGER,
    unit_id UUID REFERENCES units(id),
    CHECK ((quantity_numerator IS NULL) = (quantity_denominator IS NULL)),
    CHECK (quantity_denominator IS NULL OR quantity_denominator > 0)
);
CREATE INDEX shopping_list_item_sources_item_id_idx ON shopping_list_item_sources(shopping_list_item_id);
