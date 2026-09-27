import { state, showError } from "./state.js";
import { api, ApiError } from "./api.js";

export async function refreshRecipeList() {
  state.recipesLoading.val = true;
  try {
    const result = await api.listRecipes(state.tagFilter.val || undefined);
    state.recipes.val = result.recipes;
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not load recipes");
  } finally {
    state.recipesLoading.val = false;
  }
}

export async function loadRecipe(id) {
  state.currentRecipeLoading.val = true;
  state.currentRecipe.val = null;
  try {
    state.currentRecipe.val = await api.getRecipe(id);
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not load recipe");
  } finally {
    state.currentRecipeLoading.val = false;
  }
}

export async function loadIngredientMatches() {
  state.ingredientMatchesLoading.val = true;
  try {
    state.ingredientMatches.val = await api.listIngredientMatches();
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not load ingredient matches");
  } finally {
    state.ingredientMatchesLoading.val = false;
  }
}
