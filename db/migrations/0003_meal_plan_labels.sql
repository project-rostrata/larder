-- A meal plan is a flat list of recipes with optional free-text labels ("Monday", "for
-- guests"), not a date x slot calendar -- see docs/decisions.md. Existing rows keep their
-- recipe and multiplier; their date/slot is dropped rather than folded into a label.
ALTER TABLE meal_plan_entries
    DROP COLUMN plan_date,
    DROP COLUMN meal_slot,
    ADD COLUMN label TEXT;
