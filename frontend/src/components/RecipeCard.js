import van from "../../lib/van-1.6.1.js";
import { planRecipe } from "../mealPlan.js";
import { navigate } from "../router.js";
import { PlusIcon } from "../icons.js";

const { div, span, button } = van.tags;

// The card body opens the recipe; "+ Plan" adds it to the current meal plan. Two separate
// buttons, since a button can't contain another.
export function RecipeCard(recipe) {
  const meta = [recipe.display.servings, recipe.display.totalTime].filter(Boolean);

  return div(
    { class: "recipe-card" },
    button(
      { class: "recipe-card-main", onclick: () => navigate("recipe", { id: recipe.id }) },
      div({ class: "recipe-card-title" }, recipe.title),
      meta.length ? div({ class: "recipe-card-meta" }, meta.map((m) => span(m))) : null,
      recipe.tags.length
        ? div({ class: "recipe-card-tags" }, recipe.tags.map((t) => span({ class: "tag-chip" }, t)))
        : null,
    ),
    div(
      { class: "recipe-card-actions" },
      button(
        {
          class: "btn-ghost btn-compact",
          "aria-label": `Add ${recipe.title} to the meal plan`,
          onclick: () => planRecipe(recipe),
        },
        PlusIcon({ size: 14 }), "Plan",
      ),
    ),
  );
}
