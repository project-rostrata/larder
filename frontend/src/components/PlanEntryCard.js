import van from "../../lib/van-1.6.1.js";
import { navigate } from "../router.js";
import { removeMealPlanEntry } from "../mealPlan.js";
import { CloseIcon } from "../icons.js";

const { div, span, button } = van.tags;

// A meal-plan entry: title (a link when the API gave a recipeId), label chip, servings text.
// `removable` only for the current plan -- past plans are a record.
export function PlanEntryCard(entry, { removable = false } = {}) {
  const meta = [
    entry.label ? span({ class: "tag-chip" }, entry.label) : null,
    entry.servingsDisplay ? span(entry.servingsDisplay) : null,
  ].filter(Boolean);
  const body = [
    div({ class: "recipe-card-title" }, entry.recipeTitle),
    meta.length ? div({ class: "plan-card-meta" }, meta) : null,
  ];
  return div(
    { class: "recipe-card plan-card" },
    entry.recipeId
      ? button({ class: "recipe-card-main", onclick: () => navigate("recipe", { id: entry.recipeId }) }, ...body)
      : div({ class: "recipe-card-main static" }, ...body),
    removable
      ? button(
        {
          class: "icon-btn plan-card-remove",
          "aria-label": `Remove ${entry.recipeTitle} from the meal plan`,
          onclick: () => removeMealPlanEntry(entry),
        },
        CloseIcon({ size: 14 }),
      )
      : null,
  );
}
