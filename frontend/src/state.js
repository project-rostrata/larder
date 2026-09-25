import van from "../lib/van-1.6.1.js";

// A handful of top-level van.state() values — no store/reducer abstraction needed at this
// size, same call shelf made.
export const state = {
  authChecked: van.state(false),
  user: van.state(null), // { userId, username } | null

  // Query-param view routing (?view=..., &id=...), matching shelf's own router.js pattern —
  // see src/router.js.
  view: van.state("recipes"), // "recipes" | "recipe" | "recipe-new" | "recipe-edit" | "meal-plan" |
  //   "shopping-lists" | "shopping-list"
  currentId: van.state(null), // the ?id= of the current view (a recipe or a shopping list)

  recipes: van.state([]), // RecipeResponse[] for the current tag filter
  recipesLoading: van.state(false),
  tagFilter: van.state(""),

  currentRecipe: van.state(null), // RecipeResponse | null — loaded for "recipe"/"recipe-edit"
  currentRecipeLoading: van.state(false),

  mealPlan: van.state([]), // MealPlanEntryResponse[] — the whole list, insertion order
  mealPlanLoading: van.state(false),

  shoppingLists: van.state([]), // ShoppingListSummaryResponse[], newest first
  shoppingListsLoading: van.state(false),
  currentShoppingList: van.state(null), // ShoppingListResponse | null
  currentShoppingListLoading: van.state(false),

  error: van.state(null), // string | null — drives the Toast component
  notice: van.state(null), // string | null — non-error Toast (e.g. "Added to meal plan")
  // null | {type:"import"} | {type:"confirmDelete", recipe} | {type:"addToMealPlan", recipe}
  //   | {type:"confirmDeleteList", list}
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
