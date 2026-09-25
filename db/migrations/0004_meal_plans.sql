-- Meal plans get a history: entries now belong to a meal_plans row. Each user has at most one
-- active plan (archived_at IS NULL); starting a new plan archives the active one. See
-- docs/decisions.md.
CREATE TABLE meal_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ
);
CREATE INDEX meal_plans_owner_id_idx ON meal_plans(owner_id);
CREATE UNIQUE INDEX meal_plans_one_active_per_owner_idx ON meal_plans(owner_id) WHERE archived_at IS NULL;

ALTER TABLE meal_plan_entries ADD COLUMN meal_plan_id UUID REFERENCES meal_plans(id) ON DELETE CASCADE;

-- Everyone's existing entries become their active plan.
INSERT INTO meal_plans (owner_id, created_at)
SELECT owner_id, min(created_at) FROM meal_plan_entries GROUP BY owner_id;
UPDATE meal_plan_entries e SET meal_plan_id = p.id FROM meal_plans p WHERE p.owner_id = e.owner_id;

ALTER TABLE meal_plan_entries ALTER COLUMN meal_plan_id SET NOT NULL;
CREATE INDEX meal_plan_entries_meal_plan_id_idx ON meal_plan_entries(meal_plan_id);
