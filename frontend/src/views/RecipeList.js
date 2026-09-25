import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { navigate } from "../router.js";
import { TopBar } from "../components/TopBar.js";
import { DialogHost } from "../components/DialogHost.js";
import { RecipeCard } from "../components/RecipeCard.js";
import { PlanEntryCard } from "../components/PlanEntryCard.js";
import { PlusIcon, LinkIcon } from "../icons.js";
import { startNewPlan } from "../mealPlan.js";

const { div, input, button, h2, section } = van.tags;

function Toolbar() {
  let debounceTimer = null;
  function onTagInput(e) {
    const value = e.target.value;
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      navigate("recipes", { tag: value });
    }, 300);
  }

  return div(
    { class: "toolbar" },
    input({
      class: "tag-filter-input",
      placeholder: "Filter by tag…",
      // Pass the State object itself, not .val -- van.js then wraps this one attribute in its
      // own isolated binding (see van.js's tag(): protoOf(v) === stateProto triggers a nested
      // bind()). Reading .val directly here would instead attribute the dependency to whichever
      // ancestor reactive binding is running Toolbar()'s construction -- app.js's top-level
      // Root() binding, in this app -- causing the ENTIRE app to re-render on every tag-filter
      // navigation. See docs/decisions.md for the full writeup (same bug hit in RecipeForm.js).
      value: state.tagFilter,
      oninput: onTagInput,
    }),
    div(
      { class: "toolbar-actions" },
      button(
        { class: "btn-ghost", onclick: () => { state.activeDialog.val = { type: "import" }; } },
        LinkIcon(), "Import",
      ),
      button(
        { class: "btn-primary", onclick: () => navigate("recipe-new") },
        PlusIcon(), "New recipe",
      ),
    ),
  );
}

function Grid() {
  return div(
    { class: "recipe-grid" },
    () => {
      if (state.recipesLoading.val && state.recipes.val.length === 0) {
        return div({ class: "empty-state" }, "Loading…");
      }
      if (state.recipes.val.length === 0) {
        return div(
          { class: "empty-state" },
          state.tagFilter.val ? `No recipes tagged "${state.tagFilter.val}".` : "No recipes yet — add one to get started.",
        );
      }
      // display:contents so this wrapper (needed because one reactive binding can only return
      // one node) doesn't itself become a grid item -- its children lay out as direct children
      // of .recipe-grid instead, which is what the CSS grid-template-columns rule expects.
      return div({ style: "display:contents" }, ...state.recipes.val.map((r) => RecipeCard(r)));
    },
  );
}

// The active meal plan, as the first section of the recipes view.
function CurrentPlan() {
  return section(
    { class: "recipes-section" },
    div(
      { class: "section-header" },
      h2({ class: "recipe-section-title" }, "Current meal plan"),
      div(
        { class: "toolbar-actions" },
        button({ class: "btn-ghost btn-compact", onclick: () => navigate("shopping") }, "Shopping list"),
        button(
          { class: "btn-ghost btn-compact", onclick: () => startNewPlan({ notice: "Started a new meal plan" }) },
          "Start new plan",
        ),
      ),
    ),
    div(
      { class: "recipe-grid plan-grid" },
      () => {
        if (state.mealPlanLoading.val && state.mealPlan.val.length === 0) {
          return div({ class: "plan-empty" }, "Loading…");
        }
        if (state.mealPlan.val.length === 0) {
          return div({ class: "plan-empty" }, "Nothing planned yet — tap “+ Plan” on a recipe below.");
        }
        return div({ style: "display:contents" }, ...state.mealPlan.val.map((e) => PlanEntryCard(e, { removable: true })));
      },
    ),
  );
}

// router.js's navigate()/applyUrlToState() triggers refreshRecipeList() and refreshMealPlan()
// on every navigation to this view -- this component only renders state, it doesn't fetch.
export function RecipeList() {
  return div(
    { class: "app-shell" },
    TopBar(),
    Toolbar(),
    div(
      { class: "recipes-page" },
      CurrentPlan(),
      section(
        { class: "recipes-section" },
        div({ class: "section-header" }, h2({ class: "recipe-section-title" }, "All recipes")),
        Grid(),
      ),
    ),
    DialogHost(),
  );
}
