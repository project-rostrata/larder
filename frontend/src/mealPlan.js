import { state, showError } from "./state.js";
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

// Throws on failure -- the add dialog shows the error and stays open.
export async function addToMealPlan(body) {
  const entry = await api.addToMealPlan(body);
  state.mealPlan.val = [...state.mealPlan.val, entry];
  return entry;
}

export async function removeMealPlanEntry(entry) {
  try {
    await api.removeFromMealPlan(entry.id);
    state.mealPlan.val = state.mealPlan.val.filter((e) => e.id !== entry.id);
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not remove from meal plan");
  }
}
