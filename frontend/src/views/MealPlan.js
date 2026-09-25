import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { navigate } from "../router.js";
import { removeMealPlanEntry } from "../mealPlan.js";
import { TopBar } from "../components/TopBar.js";
import { DialogHost } from "../components/DialogHost.js";
import { CloseIcon, PlusIcon } from "../icons.js";

const { div, h1, span, button } = van.tags;

function Entry(entry) {
  const meta = [];
  if (entry.label) meta.push(span({ class: "tag-chip" }, entry.label));
  if (entry.servingsDisplay) meta.push(span(entry.servingsDisplay));

  return div(
    { class: "meal-plan-entry" },
    div(
      { class: "meal-plan-entry-main" },
      button(
        { class: "meal-plan-entry-title", onclick: () => navigate("recipe", { id: entry.recipeId }) },
        entry.recipeTitle,
      ),
      meta.length ? div({ class: "meal-plan-entry-meta" }, meta) : null,
    ),
    button(
      {
        class: "icon-btn",
        "aria-label": `Remove ${entry.recipeTitle} from the meal plan`,
        onclick: () => removeMealPlanEntry(entry),
      },
      CloseIcon(),
    ),
  );
}

function List() {
  return div(
    { class: "meal-plan-list" },
    () => {
      if (state.mealPlanLoading.val && state.mealPlan.val.length === 0) {
        return div({ class: "empty-state" }, "Loading…");
      }
      if (state.mealPlan.val.length === 0) {
        return div({ class: "empty-state" }, "Nothing planned yet — open a recipe and choose “Add to meal plan”.");
      }
      // display:contents keeps entries as direct flex children of .meal-plan-list (for gap),
      // same reason as RecipeList's grid wrapper.
      return div({ style: "display:contents" }, ...state.mealPlan.val.map(Entry));
    },
  );
}

// router.js loads state.mealPlan on navigation to this view; this component only renders it.
export function MealPlan() {
  return div(
    { class: "app-shell" },
    TopBar(),
    div(
      { class: "meal-plan" },
      div(
        { class: "recipe-detail-header" },
        h1({ class: "recipe-detail-title" }, "Meal plan"),
        button({ class: "btn-ghost", onclick: () => navigate("recipes") }, PlusIcon(), "Add recipes"),
      ),
      List(),
    ),
    DialogHost(),
  );
}
