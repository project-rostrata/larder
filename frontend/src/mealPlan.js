import { state, showError, showNotice } from "./state.js";
import { api, ApiError } from "./api.js";

export async function refreshMealPlan() {
  state.mealPlanLoading.val = true;
  try {
    state.mealPlan.val = (await api.listMealPlan()).entries;
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not load meal plan");
  } finally {
    state.mealPlanLoading.val = false;
  }
}

// One tap, no dialog: the recipe goes into the current plan as written (no label, no scaling).
export async function planRecipe(recipe) {
  try {
    const entry = await api.addToMealPlan({ recipeId: recipe.id });
    state.mealPlan.val = [...state.mealPlan.val, entry];
    showNotice(`Added "${recipe.title}" to the meal plan`);
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not add to meal plan");
  }
}

export async function removeMealPlanEntry(entry) {
  try {
    await api.removeFromMealPlan(entry.id);
    state.mealPlan.val = state.mealPlan.val.filter((e) => e.id !== entry.id);
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not remove from meal plan");
  }
}

export async function refreshMealPlanHistory() {
  state.mealPlanHistoryLoading.val = true;
  try {
    state.mealPlanHistory.val = (await api.listMealPlanHistory()).plans;
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not load past meal plans");
  } finally {
    state.mealPlanHistoryLoading.val = false;
  }
}

export async function loadMealPlan(id) {
  state.currentPlanLoading.val = true;
  state.currentPlan.val = null;
  try {
    state.currentPlan.val = await api.getMealPlan(id);
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not load meal plan");
  } finally {
    state.currentPlanLoading.val = false;
  }
}

// Starts a fresh plan (fromPlanId: seeded from a past plan). If the current plan has recipes the
// API answers ACTIVE_PLAN_EXISTS with a message; that message is shown in a confirm dialog and
// the request retried with replace: true. onDone runs after a successful start.
export async function startNewPlan({ fromPlanId = null, notice, onDone } = {}, replace = false) {
  try {
    state.mealPlan.val = (await api.startMealPlan({ fromPlanId, replace })).entries;
    if (notice) showNotice(notice);
    onDone?.();
  } catch (err) {
    if (err instanceof ApiError && err.code === "ACTIVE_PLAN_EXISTS") {
      state.activeDialog.val = {
        type: "confirm",
        title: "Replace the current meal plan?",
        message: err.message,
        confirmLabel: "Replace",
        onConfirm: () => startNewPlan({ fromPlanId, notice, onDone }, true),
      };
      return;
    }
    showError(err instanceof ApiError ? err.message : "Could not start a new meal plan");
  }
}
