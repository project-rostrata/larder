import van from "../lib/van-1.6.1.js";

// A handful of top-level van.state() values — no store/reducer abstraction needed at this
// size, same call shelf made.
export const state = {
  authChecked: van.state(false),
  user: van.state(null), // { userId, username } | null

  // Query-param view routing (?view=..., &id=...), matching shelf's own router.js pattern —
  // see src/router.js.
  view: van.state("recipes"), // "recipes" | "recipe" | "recipe-new" | "recipe-edit" |
  //   "meal-plans" (history) | "meal-plan" (one past plan) | "shopping"
  currentId: van.state(null), // the ?id= of the current view (a recipe or a shopping list)

  recipes: van.state([]), // RecipeResponse[] for the current tag filter
  recipesLoading: van.state(false),
  tagFilter: van.state(""),

  currentRecipe: van.state(null), // RecipeResponse | null — loaded for "recipe"/"recipe-edit"
  currentRecipeLoading: van.state(false),

  mealPlan: van.state([]), // the active plan's MealPlanEntryResponse[], insertion order
  mealPlanLoading: van.state(false),
  mealPlanHistory: van.state([]), // MealPlanSummaryResponse[], newest first
  mealPlanHistoryLoading: van.state(false),
  currentPlan: van.state(null), // MealPlanDetailResponse | null — a past plan being viewed
  currentPlanLoading: van.state(false),

  // The current meal plan's list (ShoppingListResponse), or null with a message from the API
  // (e.g. the plan is empty).
  currentShoppingList: van.state(null),
  currentShoppingListMessage: van.state(null),
  currentShoppingListLoading: van.state(false),

  error: van.state(null), // string | null — drives the Toast component
  notice: van.state(null), // string | null — non-error Toast (e.g. "Added to meal plan")
  // null | {type:"import"} | {type:"confirmDelete", recipe}
  //   | {type:"confirm", title, message, confirmLabel, onConfirm}
  activeDialog: van.state(null),
};

let errorTimer = null;

export function showError(message) {
  state.notice.val = null;
  state.error.val = message;
  clearTimeout(errorTimer);
  errorTimer = setTimeout(() => {
    state.error.val = null;
  }, 6000);
}

let noticeTimer = null;

// The newest message wins: a notice clears any older error, and showError clears any notice.
export function showNotice(message) {
  state.error.val = null;
  state.notice.val = message;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => {
    state.notice.val = null;
  }, 3000);
}
