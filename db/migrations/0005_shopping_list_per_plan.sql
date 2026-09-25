-- The shopping list is now simply the current meal plan's list: at most one per plan, rebuilt
-- from scratch whenever the plan changes. Lists created before this have no meal_plan_id and
-- are no longer shown; they're left in place rather than deleted. See docs/decisions.md.
ALTER TABLE shopping_lists ADD COLUMN meal_plan_id UUID REFERENCES meal_plans(id) ON DELETE CASCADE;
CREATE UNIQUE INDEX shopping_lists_meal_plan_id_idx ON shopping_lists(meal_plan_id) WHERE meal_plan_id IS NOT NULL;
