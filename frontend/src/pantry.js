import { state, showError, showNotice } from "./state.js";
import { api, ApiError } from "./api.js";
import { loadCurrentShoppingList } from "./shoppingLists.js";

const message = (err, fallback) => (err instanceof ApiError ? err.message : fallback);

export async function loadPantry() {
  state.pantryLoading.val = true;
  try {
    state.pantry.val = await api.getPantry();
  } catch (err) {
    showError(message(err, "Could not load your pantry"));
  } finally {
    state.pantryLoading.val = false;
  }
}

// Pantry page actions: each API call returns the whole pantry. Resolves true on success.
export async function addToPantryByName(name) {
  try {
    state.pantry.val = await api.addToPantry({ name });
    return true;
  } catch (err) {
    showError(message(err, "Could not add to your pantry"));
    return false;
  }
}

export async function removeFromPantry(ingredientId) {
  try {
    state.pantry.val = await api.removeFromPantry(ingredientId);
  } catch (err) {
    showError(message(err, "Could not remove from your pantry"));
  }
}

// From a shopping-list row's ⋯ menu. The pantry is applied when the list is read, so the list
// is just reloaded -- checks and order are untouched.
export async function setPantryFromList(item, inPantry) {
  try {
    if (inPantry) await api.addToPantry({ ingredientId: item.ingredientId });
    else await api.removeFromPantry(item.ingredientId);
    await loadCurrentShoppingList();
    showNotice(inPantry ? `"${item.nameDisplay}" is in your pantry` : `"${item.nameDisplay}" is no longer in your pantry`);
  } catch (err) {
    showError(message(err, "Could not update your pantry"));
  }
}
