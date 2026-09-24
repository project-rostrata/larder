import van from "../lib/van-1.6.1.js";

// A handful of top-level van.state() values — no store/reducer abstraction needed at this
// size, same call shelf made.
export const state = {
  authChecked: van.state(false),
  user: van.state(null), // { userId, username } | null

  // Query-param view routing (?view=..., &id=...), matching shelf's own router.js pattern —
  // see src/router.js.
  view: van.state("recipes"), // "recipes" | "recipe" | "recipe-new" | "recipe-edit"
  currentRecipeId: van.state(null),

  recipes: van.state([]), // RecipeResponse[] for the current tag filter
  recipesLoading: van.state(false),
  tagFilter: van.state(""),

  currentRecipe: van.state(null), // RecipeResponse | null — loaded for "recipe"/"recipe-edit"
  currentRecipeLoading: van.state(false),

  error: van.state(null), // string | null — drives the Toast component
  activeDialog: van.state(null), // null | {type:"import"} | {type:"confirmDelete", recipe}
};

let errorTimer = null;

export function showError(message) {
  state.error.val = message;
  clearTimeout(errorTimer);
  errorTimer = setTimeout(() => {
    state.error.val = null;
  }, 6000);
}
