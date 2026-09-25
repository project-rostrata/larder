-- Free-text notes on a recipe (tips, a description, substitutions) -- first needed to keep the
-- "description" and "tool" tips a Nextcloud Cookbook recipe.json carries. Paragraphs separated
-- by a blank line.
ALTER TABLE recipes ADD COLUMN notes TEXT;
